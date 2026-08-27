package com.miniagent.config.security;

import com.miniagent.config.entity.Tenant;
import com.miniagent.config.entity.User;
import com.miniagent.config.repository.TenantRepository;
import com.miniagent.config.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

@Component
public class SignedSessionFilter extends OncePerRequestFilter {

    @Autowired

    private SessionCookieService sessionCookieService;

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
        Long userId = sessionCookieService.resolveUserId(request);
        AuthenticatedUser principal = resolvePrincipal(userId);
        if (Objects.nonNull(principal)) {
            request.setAttribute(SessionCookieService.ATTR_USER_ID, principal.userId());
            request.setAttribute(SessionCookieService.ATTR_PRINCIPAL, principal);
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(new UsernamePasswordAuthenticationToken(
                    principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + principal.role().name()))));
            SecurityContextHolder.setContext(context);
        } else {
            SecurityContextHolder.clearContext();
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
