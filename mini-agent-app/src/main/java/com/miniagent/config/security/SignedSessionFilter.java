package com.miniagent.config.security;

import com.miniagent.config.entity.Tenant;
import com.miniagent.config.entity.User;
import com.miniagent.config.repository.TenantRepository;
import com.miniagent.config.repository.UserRepository;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * JWT 认证过滤器：解析 {@code Authorization: Bearer} 头，命中 Redis 会话就放行并把
 * 用户信息放进 request 属性（{@link JwtSessionService#ATTR_USER_ID} /
 * {@link JwtSessionService#ATTR_PRINCIPAL}）与 SecurityContext。
 *
 * <p>token 缺失或已失效时，本过滤器<b>直接返回 401</b>，不再只清 SecurityContext 后
 * 交给 {@code authorizeHttpRequests} + {@code authenticationEntryPoint} 兜底 ——
 * 前端需要尽早拿到 401 才能清掉本地 token 并跳登录页，多穿一层没有收益。
 *
 * <p>三类请求不受此限制：公开路径（见 {@link SecurityConfig#PUBLIC_PATHS}）、
 * {@code ERROR} 派发（否则错误页会被本过滤器自己的 401 覆盖）、
 * {@code ASYNC} 派发（SSE 的异步续跑会再次经过过滤链，原始请求已经校验过）。
 */
@Component
public class SignedSessionFilter extends OncePerRequestFilter {

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    @Autowired
    private JwtSessionService jwtSessionService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TenantRepository tenantRepository;

    /** SSE/异步派发必须重新挂载认证，否则 async dispatch 会 Access Denied */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // 放行 OPTIONS 预检请求，避免干扰 CORS
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }

        // 浏览器自动探测、服务端从不提供的路径（如 Chrome 开 DevTools 时发的
        // /.well-known/appspecific/com.chrome.devtools.json）：直接 404。
        // 放在鉴权之前 —— 回 401 会让前端把"这个资源不存在"误判成"登录失效"并跳登录页。
        if (isBrowserProbe(request)) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        Long userId = jwtSessionService.resolveUserIdAndRefresh(request);
        AuthenticatedUser principal = resolvePrincipal(userId);
        if (Objects.nonNull(principal)) {
            request.setAttribute(JwtSessionService.ATTR_USER_ID, principal.userId());
            request.setAttribute(JwtSessionService.ATTR_PRINCIPAL, principal);
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(new UsernamePasswordAuthenticationToken(
                    principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + principal.role().name()))));
            SecurityContextHolder.setContext(context);
        } else {
            SecurityContextHolder.clearContext();
            if (requiresAuth(request)) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"error\":\"Not authenticated\"}");
                return;
            }
        }
        try {
            filterChain.doFilter(request, response);
        } finally {
            // 初始请求若已 startAsync，勿清掉上下文，留给异步派发；派发结束时再清
            if (!request.isAsyncStarted()) {
                SecurityContextHolder.clearContext();
            }
        }
    }

    /**
     * 这个请求是否必须携带有效 token。
     *
     * <p>白名单直接引用 {@link SecurityConfig#PUBLIC_PATHS}，与授权规则共用一份 ——
     * 两处各写一份的话，早晚会出现「filter 放行了但 Spring 授权拒绝」或反过来的漂移。
     */
    private boolean requiresAuth(HttpServletRequest request) {
        DispatcherType type = request.getDispatcherType();
        if (type == DispatcherType.ERROR || type == DispatcherType.ASYNC) {
            return false;
        }
        String path = stripContextPath(request);
        for (String pattern : SecurityConfig.PUBLIC_PATHS) {
            if (MATCHER.match(pattern, path)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 是否属于"浏览器自动探测、服务端从不提供"的路径，见
     * {@link SecurityConfig#BROWSER_PROBE_PATHS}。共用同一份列表，避免两处各写一份后漂移。
     */
    private boolean isBrowserProbe(HttpServletRequest request) {
        String path = stripContextPath(request);
        for (String pattern : SecurityConfig.BROWSER_PROBE_PATHS) {
            if (MATCHER.match(pattern, path)) {
                return true;
            }
        }
        return false;
    }

    /** 去掉 contextPath 后的请求路径；匹配用的都是应用内路径，不带部署前缀。 */
    private static String stripContextPath(HttpServletRequest request) {
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        return path;
    }

    private AuthenticatedUser resolvePrincipal(Long userId) {
        if (userId == null) {
            return null;
        }
        return userRepository.findById(userId)
                .filter(User::isEnabled)
                .flatMap(user -> tenantRepository.findById(user.getTenantId())
                        .filter(Tenant::isEnabled)
                        .map(tenant -> new AuthenticatedUser(
                                user.getId(), tenant.getId(), user.getUsername(), user.getRole())))
                .orElse(null);
    }
}
