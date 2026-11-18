package com.miniagent.account.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.common.ApiResponse;
import com.miniagent.common.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Protects cookie-authenticated portal mutations with a same-origin double-submit token. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AccountPortalCsrfFilter extends OncePerRequestFilter {
    private static final String HEADER = "X-CSRF-TOKEN";

    @Value("${server.servlet.session.cookie.secure:false}")
    private boolean secureCookie;

    private final ObjectMapper objectMapper;

    public AccountPortalCsrfFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String path = applicationPath(request);
        return !path.startsWith("/api/portal/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String expected = csrfCookie(request);
        String provided = request.getHeader(HEADER);
        if (!validToken(expected, provided)) {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.setContentType("application/json;charset=UTF-8");
            response.setHeader("Cache-Control", "no-store");
            response.getWriter().write(objectMapper.writeValueAsString(
                    ApiResponse.fail(ErrorCode.AUTH_FORBIDDEN,
                            "门户请求缺少有效的 CSRF token，请刷新页面后重试")));
            return;
        }
        chain.doFilter(request, response);
    }

    private String csrfCookie(HttpServletRequest request) {
        String expectedName = secureCookie
                ? PortalController.SECURE_CSRF_COOKIE : PortalController.CSRF_COOKIE;
        String value = null;
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie cookie : cookies) {
            if (expectedName.equals(cookie.getName())) {
                // Duplicate cookie names are ambiguous and can be abused for cookie tossing.
                if (value != null) return null;
                value = cookie.getValue();
            }
        }
        return value;
    }

    static boolean validToken(String cookie, String header) {
        if (cookie == null || header == null || cookie.length() != 43 || header.length() != 43) {
            return false;
        }
        if (!cookie.matches("[A-Za-z0-9_-]{43}") || !header.matches("[A-Za-z0-9_-]{43}")) {
            return false;
        }
        return MessageDigest.isEqual(cookie.getBytes(StandardCharsets.US_ASCII),
                header.getBytes(StandardCharsets.US_ASCII));
    }

    private static String applicationPath(HttpServletRequest request) {
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && path.startsWith(contextPath)) {
            return path.substring(contextPath.length());
        }
        return path;
    }
}
