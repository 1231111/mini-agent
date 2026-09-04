package com.miniagent.config.security;

import org.springframework.beans.factory.annotation.Value;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.firewall.HttpFirewall;
import org.springframework.security.web.firewall.StrictHttpFirewall;
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
 *     <li><b>CSRF 防护</b>：基于 Cookie 的 CSRF Token 机制，防止跨站请求伪造攻击</li>
 *     <li><b>CORS 跨域</b>：支持 Electron 桌面端和 Web 端的跨域访问</li>
 *     <li><b>会话管理</b>：无状态（STATELESS），所有请求依赖 JWT 认证</li>
 *     <li><b>请求授权</b>：公开路径免认证，其余路径需要有效 JWT Token</li>
 *     <li><b>异常处理</b>：统一处理未认证（401）和访问拒绝（403）响应</li>
 * </ul>
 *
 * <h3>Filter 执行顺序（从先到后）</h3>
 * <pre>
 *   1. CorsFilter           —— 最先执行，确保 OPTIONS 预检请求不被后续 Filter 拦截
 *   2. RateLimitFilter      —— 速率限制（每用户/IP 限流）
 *   3. SignedSessionFilter  —— JWT 认证，解析 Token 并设置 SecurityContext
 *   4. Spring Security 内置 Filter 链（CSRF、授权等）
 *   5. CsrfCookieFilter     —— 在响应中写入 XSRF-TOKEN Cookie
 * </pre>
 *
 * <h3>公开访问路径（无需认证）</h3>
 * <ul>
 *     <li>{@code /} {@code /login} {@code /error} —— 页面路由</li>
 *     <li>{@code /api/login} {@code /api/register} {@code /api/auth-status} {@code /api/logout} —— 认证接口</li>
 *     <li>{@code /actuator/health} {@code /actuator/info} —— 监控端点</li>
 *     <li>{@code /css/**} {@code /js/**} {@code /favicon.ico} {@code /static/**} —— 静态资源</li>
 * </ul>
 *
 * <h3>可配置项</h3>
 * <ul>
 *     <li>{@code agent.auth.allowed-origins} —— CORS 允许的来源，默认 {@code http://localhost:*,http://127.0.0.1:*,file://}</li>
 *     <li>{@code agent.auth.bcrypt-strength} —— BCrypt 加密强度，默认 {@code 10}，范围 10~16</li>
 * </ul>
 *
 * @see SignedSessionFilter
 * @see RateLimitFilter
 * @see CsrfCookieFilter
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {
    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

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
                "X-CSRF-Error",
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
                                                   RateLimitFilter rateLimitFilter,
                                                   CsrfCookieFilter csrfCookieFilter) throws Exception {
        http
                // 1. CSRF 配置
                .csrf(this::configureCsrf)
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
                .addFilterBefore(signedSessionFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(csrfCookieFilter, CsrfFilter.class);
        return http.build();
    }

    /**
     * 配置 CSRF（跨站请求伪造）防护
     */
    private void configureCsrf(org.springframework.security.config.annotation.web.configurers.CsrfConfigurer<HttpSecurity> csrf) {
        csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler());
    }

    /**
     * 配置会话管理策略（无状态）
     */
    private void configureSessionManagement(org.springframework.security.config.annotation.web.configurers.SessionManagementConfigurer<HttpSecurity> sm) {
        sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS);
    }

    /**
     * 配置安全响应头
     */
    private void configureSecurityHeaders(org.springframework.security.config.annotation.web.configurers.HeadersConfigurer<HttpSecurity> headers) {
        headers
            .frameOptions(frameOptions -> frameOptions.sameOrigin());
    }

    /**
     * 配置请求授权规则
     */
    private void configureAuthorization(AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry auth) {
        auth
                .requestMatchers(
                        "/",
                        "/login",
                        "/error",
                        "/api/login",
                        "/api/register",
                        "/api/auth-status",
                        "/api/logout",
                        "/actuator/health",
                        "/actuator/info",
                        "/css/**",
                        "/js/**",
                        "/favicon.ico"
                ).permitAll()
                // 静态资源（GET 请求）
                .requestMatchers(HttpMethod.GET, "/static/css/**", "/static/js/**").permitAll()
                // 其他所有请求需要认证
                .anyRequest().authenticated();
    }

    /**
     * 配置异常处理（认证入口点和访问拒绝处理器）
     */
    private void configureExceptionHandling(org.springframework.security.config.annotation.web.configurers.ExceptionHandlingConfigurer<HttpSecurity> ex) {
        ex
                // 未认证处理器
                .authenticationEntryPoint((req, res, e) -> {
                    String accept = req.getHeader("Accept");
                    boolean wantsHtml = Objects.nonNull(accept) && accept.contains("text/html");
                    if (wantsHtml && !req.getRequestURI().startsWith("/api")
                            && !req.getRequestURI().startsWith("/chat")) {
                        res.sendRedirect("/");
                    } else {
                        res.setStatus(401);
                        res.setContentType("application/json;charset=UTF-8");
                        res.getWriter().write("{\"error\":\"Not authenticated\"}");
                    }
                })
                // 访问拒绝处理器
                .accessDeniedHandler(this::handleAccessDenied);
    }

    /**
     * 处理访问拒绝异常
     */
    private void handleAccessDenied(HttpServletRequest req, HttpServletResponse res, org.springframework.security.access.AccessDeniedException e) throws IOException {
        boolean csrfFailure = e instanceof CsrfException;
        if (csrfFailure) {
            handleCsrfFailure(req, res);
            return;
        }
        
        log.warn("Access denied method={} path={} reason={}",
                req.getMethod(), req.getRequestURI(), e.getClass().getSimpleName());
        sendForbiddenResponse(res, "Forbidden");
    }

    /**
     * 处理 CSRF 验证失败
     */
    private void handleCsrfFailure(HttpServletRequest req, HttpServletResponse res) throws IOException {
        boolean cookiePresent = req.getCookies() != null
                && Arrays.stream(req.getCookies())
                .anyMatch(cookie -> "XSRF-TOKEN".equals(cookie.getName()));
        boolean headerPresent = req.getHeader("X-XSRF-TOKEN") != null;
        
        log.warn("CSRF request rejected method={} path={} cookiePresent={} headerPresent={}",
                req.getMethod(), req.getRequestURI(), cookiePresent, headerPresent);
        
        res.setHeader("X-CSRF-Error", "true");
        sendForbiddenResponse(res, "CSRF token missing or invalid");
    }

    /**
     * 发送 403 Forbidden 响应
     */
    private void sendForbiddenResponse(HttpServletResponse res, String message) throws IOException {
        res.setStatus(403);
        res.setContentType("application/json;charset=UTF-8");
        res.getWriter().write("{\"success\":false,\"code\":\"AUTH.02.02\",\"message\":\"" + message + "\"}");
    }
}
