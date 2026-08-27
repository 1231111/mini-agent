package com.miniagent.config.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class RequestCorrelationFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(RequestCorrelationFilter.class);
    private static final String HEADER = "X-Request-ID";
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._:-]{8,80}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String supplied = request.getHeader(HEADER);
        String requestId = supplied != null && SAFE_ID.matcher(supplied).matches()
                ? supplied : UUID.randomUUID().toString();
        long startedAt = System.nanoTime();
        MDC.put("requestId", requestId);
        response.setHeader(HEADER, requestId);
        boolean logRequest = shouldLogRequest(request);
        if (logRequest) {
            log.info("HTTP request started method={} path={} requestId={}",
                    request.getMethod(), request.getRequestURI(), requestId);
        }
        try {
            filterChain.doFilter(request, response);
        } finally {
            if (logRequest) {
                long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
                log.info("HTTP request completed method={} path={} status={} durationMs={} requestId={}",
                        request.getMethod(), request.getRequestURI(), response.getStatus(),
                        elapsedMs, requestId);
            }
            MDC.remove("requestId");
        }
    }

    private static boolean shouldLogRequest(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !(path.startsWith("/css/")
                || path.startsWith("/js/")
                || path.startsWith("/static/")
                || path.startsWith("/actuator/")
                || "/favicon.ico".equals(path));
    }
}
