package com.miniagent.config.security;

import org.springframework.beans.factory.annotation.Value;
import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.firewall.HttpFirewall;
import org.springframework.security.web.firewall.StrictHttpFirewall;
import org.springframework.http.HttpMethod;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Spring Security 核心配置类
 *
 * <p>统一管理应用的安全策略，包括：
 * <ul>
 *     <li><b>CSRF</b>：整体关闭。认证凭证只走 {@code Authorization: Bearer} 头、由前端 JS 显式设置，
 *         跨站请求无法自动携带它，传统的 CSRF 攻击面不成立；同时「无 cookie、无 session」也意味着
 *         Spring 标准的 CsrfTokenRepository 没有任何可用的承载位置。</li>
 *     <li><b>CORS 跨域</b>：支持 Electron 桌面端和 Web 端的跨域访问</li>
 *     <li><b>会话管理</b>：无状态（STATELESS），所有请求依赖 JWT 认证</li>
 *     <li><b>请求授权</b>：公开路径免认证，其余路径需要有效 JWT Token</li>
 *     <li><b>异常处理</b>：未认证返回 401（主要由过滤器直接给出），访问拒绝返回 403</li>
 * </ul>
 *
 * <h3>Filter 执行顺序（从先到后）</h3>
 * <pre>
 *   1. CorsFilter           —— 最先执行，确保 OPTIONS 预检请求不被后续 Filter 拦截
 *   2. RateLimitFilter      —— 速率限制（每用户/IP 限流）
 *   3. SignedSessionFilter  —— JWT 认证；无有效 token 且非公开路径时直接返回 401
 *   4. Spring Security 内置 Filter 链（授权等）
 * </pre>
 *
 * <h3>公开访问路径（无需认证）</h3>
 * <p>见 {@link #PUBLIC_PATHS}。该常量同时被 {@link SignedSessionFilter} 与
 * {@link #configureAuthorization} 引用，避免两处白名单各写一份后漂移。
 *
 * <h3>可配置项</h3>
 * <ul>
 *     <li>{@code agent.auth.allowed-origins} —— CORS 允许的来源，默认 {@code http://localhost:*,http://127.0.0.1:*,file://}</li>
 *     <li>{@code agent.auth.bcrypt-strength} —— BCrypt 加密强度，默认 {@code 10}，范围 10~16</li>
 * </ul>
 *
 * @see SignedSessionFilter
 * @see RateLimitFilter
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {
    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    /**
     * 免认证路径。{@link SignedSessionFilter} 直接据此判断「要不要强制要求 token」，
     * 授权规则也引用同一份，保证「filter 放行的」和「Spring 授权放行的」始终一致。
     *
     * <p>注意 {@code /}、{@code /login}、{@code /trace}、{@code /membership} 必须放行：
     * 无 cookie 之后服务端在页面
     * 导航请求上拿不到任何身份信息（浏览器导航无法携带自定义头），页面入口只能由前端读
     * sessionStorage 后自行分流。放行页面骨架不等于放行数据 —— 页面里的每个
     * {@code /api/**} 调用仍要带 Bearer。
     */
    public static final String[] PUBLIC_PATHS = {
            "/",
            "/login",
            "/trace",
            // 会员中心的页面骨架。它放行的理由和 /login 完全相同：这是个页面导航请求，
            // 带不了 Authorization 头，若在这里拦，浏览器直接看不到这个页面。
            // 页面里的 /api/membership/** 一条都不在放行名单里，仍逐个要 Bearer，
            // 而真正的 userId 是从那个 Bearer 里解出来的（见 MembershipController）。
            "/membership",
            "/error",
            "/api/tokens",
            "/api/tokens/current",
            "/api/users",
            // 云端账号服务状态：登录页在未登录态就要能问"云连得上吗"，
            // 否则用户输完密码才收到网络错误，会误以为是账号问题。
            // 这条只返回 {enabled, reachable, error}，不含云端地址等部署细节。
            "/api/auth/cloud-status",
            // 网页注册完成后，浏览器用自定义协议把一次性凭证交回尚未登录的客户端。
            "/api/auth/desktop-login",
            "/actuator/health",
            "/actuator/info",
            "/css/**",
            "/js/**",
            "/static/**",
            // 静态页面骨架（classpath:/static/*.html 直接映射在根路径下，不受 /static/** 覆盖）：
            // agent-dynamic-trace.html / agent-realtime-trace.html / trace-visualization.html。
            // 放行的只是 HTML 外壳，页面里的 /api/** 调用仍逐个要求 Bearer。
            "/*.html",
            "/favicon.ico"
    };

    /**
     * 浏览器自动探测、而本服务<b>从不提供</b>的路径。
     *
     * <p>与 {@link #PUBLIC_PATHS} 的区别是语义，不是措辞：白名单里的东西是"存在、但免认证"，
     * 这里的路径是"根本不存在、但 Chromium 系浏览器会主动来问"。典型的是
     * {@code /.well-known/appspecific/com.chrome.devtools.json} —— 打开 DevTools 时 Chromium
     * 必发一次，用来发现 Workspace 映射配置。
     *
     * <p>对这些请求回 401 是错的，代价不只是日志难看：
     * <ul>
     *   <li>401 的语义是"带上凭证再来"，可服务端永远不会提供它，客户端重试多少次都一样；</li>
     *   <li>{@code static/js/security.js} 对所有同源 401 一律清 token 并跳登录页，
     *       所以任何经 {@code window.fetch} 发出的探测请求都会把用户踢出登录态。</li>
     * </ul>
     * 正确响应是 404。
     *
     * <p>将来若要真的提供 {@code /.well-known/security.txt} 之类的资源，必须把它从本常量移到
     * {@link #PUBLIC_PATHS}，否则会被这里当成不存在直接 404 掉。
     */
    public static final String[] BROWSER_PROBE_PATHS = {
            "/.well-known/**",
            "/robots.txt",
            "/sitemap.xml"
    };

    @Value("${agent.auth.allowed-origins:http://localhost:*,http://127.0.0.1:*,file://}")
    private String allowedOrigins;
    @Value("${agent.auth.bcrypt-strength:10}")
    private int bcryptStrength;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(Math.max(10, Math.min(bcryptStrength, 16)));
    }

    /**
     * CORS 配置，支持 Electron 桌面端和 Web 端访问
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList());
        configuration.setAllowedMethods(Arrays.asList(
                "GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"
        ));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);
        // 暴露 SSE 相关的头
        configuration.setExposedHeaders(Arrays.asList(
                "X-Request-Id",
                "Cache-Control",
                "Content-Type"
        ));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    /** Keep Spring's strict request-target and header validation enabled. */
    @Bean
    public HttpFirewall httpFirewall() {
        return new StrictHttpFirewall();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   SignedSessionFilter signedSessionFilter,
                                                   RateLimitFilter rateLimitFilter) throws Exception {
        http
                // 1. CSRF：整体关闭（凭证只走 Authorization 头，且无 cookie/session 可承载 token）
                .csrf(AbstractHttpConfigurer::disable)
                // 2. CORS 配置
                .cors(Customizer.withDefaults())
                // 3. 会话管理（无状态）
                .sessionManagement(this::configureSessionManagement)
                // 4. 安全头配置
                .headers(this::configureSecurityHeaders)
                // 5. 授权规则配置
                .authorizeHttpRequests(this::configureAuthorization)
                // 6. 异常处理配置
                .exceptionHandling(this::configureExceptionHandling)
                // 7. 过滤器顺序配置
                .addFilterBefore(new org.springframework.web.filter.CorsFilter(corsConfigurationSource()), UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(rateLimitFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(signedSessionFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /**
     * 配置会话管理策略（无状态）
     */
    private void configureSessionManagement(org.springframework.security.config.annotation.web.configurers.SessionManagementConfigurer<HttpSecurity> sm) {
        sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS);
    }

    /**
     * 安全响应头。
     *
     * <p>CSP 是纵深防御，不是 XSS 的修复手段：页面里有 4k 行内联 JS 与大量行内事件处理器，
     * 所以 script-src 必须保留 'unsafe-inline'（保留它意味着行内 onerror= 仍会执行）。
     * 真正的修复在渲染层（chat.html 的 renderMD 先转义再标记），这里补的是另一类攻击面：
     * 禁止外域脚本被加载、禁止 object/embed/frame 注入、禁止 &lt;base&gt; 劫持相对 URL。</p>
     *
     * <p>img/connect 放开 https 与 data:/blob: 是因为交付物里会有远程图片与本地生成的媒体。</p>
     */
    private void configureSecurityHeaders(org.springframework.security.config.annotation.web.configurers.HeadersConfigurer<HttpSecurity> headers) {
        headers
            .frameOptions(frameOptions -> frameOptions.sameOrigin())
            .contentTypeOptions(Customizer.withDefaults())
            .referrerPolicy(referrer -> referrer
                    .policy(org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter
                            .ReferrerPolicy.NO_REFERRER))
            .contentSecurityPolicy(csp -> csp.policyDirectives(
                    "default-src 'self'; "
                            + "script-src 'self' 'unsafe-inline'; "
                            + "style-src 'self' 'unsafe-inline'; "
                            + "img-src 'self' data: blob: https:; "
                            + "media-src 'self' data: blob: https:; "
                            + "font-src 'self' data:; "
                            + "connect-src 'self'; "
                            + "object-src 'none'; "
                            + "base-uri 'none'; "
                            + "form-action 'self'; "
                            + "frame-ancestors 'self'"));
    }

    /**
     * 配置请求授权规则
     */
    private void configureAuthorization(AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry auth) {
        auth
                .requestMatchers(PUBLIC_PATHS).permitAll()
                .requestMatchers("/api/admin/**").hasRole("SYSTEM_ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/mcp/servers/**")
                .hasRole("SYSTEM_ADMIN")
                // 其他所有请求需要认证
                .anyRequest().authenticated();
    }

    /**
     * 配置异常处理（认证入口点和访问拒绝处理器）
     */
    private void configureExceptionHandling(org.springframework.security.config.annotation.web.configurers.ExceptionHandlingConfigurer<HttpSecurity> ex) {
        ex
                // 未认证处理器：常态下 401 已由 SignedSessionFilter 前移给出，
                // 这里只兜底 filter 之后才可能出现的拒绝路径（如方法级授权）。
                .authenticationEntryPoint((req, res, e) -> {
                    res.setStatus(401);
                    res.setContentType("application/json;charset=UTF-8");
                    res.getWriter().write("{\"error\":\"Not authenticated\"}");
                })
                // 访问拒绝处理器
                .accessDeniedHandler(this::handleAccessDenied);
    }

    /**
     * 处理访问拒绝异常
     */
    private void handleAccessDenied(HttpServletRequest req, HttpServletResponse res,
                                    org.springframework.security.access.AccessDeniedException e) throws IOException {
        log.warn("Access denied method={} path={} reason={}",
                req.getMethod(), req.getRequestURI(), e.getClass().getSimpleName());
        res.setStatus(403);
        res.setContentType("application/json;charset=UTF-8");
        res.getWriter().write("{\"success\":false,\"code\":\"AUTH.02.02\",\"message\":\"Forbidden\"}");
    }
}
