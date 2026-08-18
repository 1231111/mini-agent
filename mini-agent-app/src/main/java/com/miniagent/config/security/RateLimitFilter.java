package com.miniagent.config.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple sliding-window rate limit per authenticated user (or IP if anonymous).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class RateLimitFilter extends OncePerRequestFilter {

    @Value("${agent.rate-limit.per-minute:60}")
    private int perMinuteConfig;
    private int perMinute = 60;
    @Autowired
    private SessionCookieService sessionCookieService;
    /** 最大限流窗口数，防止 OOM */
    private static final int MAX_WINDOWS = 10_000;
    private final Map<String, Deque<Long>> windows = new ConcurrentHashMap<>();

    @PostConstruct
    private void initRate() {
        this.perMinute = Math.max(1, perMinuteConfig);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/actuator")
                || path.startsWith("/generated-images")
                || path.startsWith("/css")
                || path.startsWith("/js");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String key = rateKey(request);
        long now = System.currentTimeMillis();
        long cutoff = now - 60_000L;
        // 防止 OOM：窗口数超限时拒绝新 key
        if (windows.size() >= MAX_WINDOWS && !windows.containsKey(key)) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"rate limit exceeded\"}");
            return;
        }
        Deque<Long> q = windows.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && q.peekFirst() < cutoff) q.pollFirst();
            if (q.size() >= perMinute) {
                response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"error\":\"rate limit exceeded\"}");
                return;
            }
            q.addLast(now);
        }
        filterChain.doFilter(request, response);
    }

    private String rateKey(HttpServletRequest request) {
        Long uid = sessionCookieService.resolveUserId(request);
        if (uid != null) return "u:" + uid;
        String ip = request.getRemoteAddr();
        return "ip:" + (ip == null ? "unknown" : ip);
    }
}
