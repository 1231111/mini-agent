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
import org.springframework.util.AntPathMatcher;
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
    private static final AntPathMatcher MATCHER = new AntPathMatcher();

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

    /**
     * 这些请求不打 INFO。
     *
     * <p>两类：一是静态资源（量最大、无信息量）；二是浏览器自动探测的路径
     * （{@link SecurityConfig#BROWSER_PROBE_PATHS}，Chromium 每次打开 DevTools 都会来问
     * {@code /.well-known/appspecific/com.chrome.devtools.json}，一条请求打两行 INFO，
     * 会把真正有用的请求记录冲掉）。这两类都由 SignedSessionFilter 直接短路处理，本身也没有业务含义。
     */
    private static boolean shouldLogRequest(HttpServletRequest request) {
        String path = stripContextPath(request);
        for (String pattern : SecurityConfig.BROWSER_PROBE_PATHS) {
            if (MATCHER.match(pattern, path)) {
                return false;
            }
        }
        return !(path.startsWith("/css/")
                || path.startsWith("/js/")
                || path.startsWith("/static/")
                || path.startsWith("/actuator/")
                || "/favicon.ico".equals(path));
    }

    private static String stripContextPath(HttpServletRequest request) {
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        return path;
    }
}
