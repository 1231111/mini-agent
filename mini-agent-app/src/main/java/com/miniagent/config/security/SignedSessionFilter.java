package com.miniagent.config.security;

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

@Component
public class SignedSessionFilter extends OncePerRequestFilter {

    private final SessionCookieService sessionCookieService;

    public SignedSessionFilter(SessionCookieService sessionCookieService) {
        this.sessionCookieService = sessionCookieService;
    }

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
        if (userId != null) {
            request.setAttribute(SessionCookieService.ATTR_USER_ID, userId);
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(new UsernamePasswordAuthenticationToken(
                    userId, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
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
}
