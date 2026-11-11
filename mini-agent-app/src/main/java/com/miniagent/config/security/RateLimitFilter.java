package com.miniagent.config.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import com.miniagent.config.security.JwtSessionService;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sliding-window rate limit per authenticated user (or IP if anonymous).
 * Frequent read-only task polling has a separate budget so UI refreshes cannot consume
 * the same budget as user-triggered requests.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();
    /** 高频只读接口走单独额度；流订阅仍走普通限流，避免开放无限 SSE 连接。 */
    private static final String[] BACKGROUND_READ_PATHS = {
            "/api/conversations/*/task",
            "/api/conversations/*/inspector-snapshot"
    };

    @Value("${agent.rate-limit.per-minute:60}")
    private int perMinuteConfig;
    private int perMinute = 60;
    @Value("${agent.rate-limit.background-read-per-minute:180}")
    private int backgroundReadPerMinuteConfig;
    private int backgroundReadPerMinute = 180;
    @Autowired
    private JwtSessionService jwtSessionService;
    /** 最大限流窗口数，防止 OOM */
    private static final int MAX_WINDOWS = 10_000;
    /** 空闲窗口超过一个限流周期后即可回收。 */
    private static final long WINDOW_IDLE_MILLIS = 60_000L;
    private final Map<String, Deque<Long>> windows = new ConcurrentHashMap<>();
    /**
     * {@link #windows} 里只保存请求时间戳；单独记录最后访问时间可以在窗口已经
     * 过期、但 deque 为空时也及时回收 key。该 map 与 windows 一起在 map 锁下更新。
     */
    /** Access-order map: expired cleanup and LRU eviction are amortized O(1). */
    private final Map<String, Long> lastAccess = new LinkedHashMap<>(128, 0.75f, true);

    @PostConstruct
    private void initRate() {
        this.perMinute = Math.max(1, perMinuteConfig);
        this.backgroundReadPerMinute = Math.max(1, backgroundReadPerMinuteConfig);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = applicationPath(request);
        // 放行 OPTIONS 预检请求，避免干扰 CORS
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        return path.startsWith("/actuator")
                || path.startsWith("/css")
                || path.startsWith("/js");
    }

    private static String applicationPath(HttpServletRequest request) {
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && path.startsWith(contextPath)) {
            return path.substring(contextPath.length());
        }
        return path;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        boolean backgroundRead = isBackgroundRead(request);
        String key = rateKey(request, backgroundRead);
        int requestLimit = backgroundRead ? backgroundReadPerMinute : perMinute;
        long now = System.currentTimeMillis();
        long cutoff = now - WINDOW_IDLE_MILLIS;

        /*
         * A map that only ever grows turns an IP based limiter into a trivial DoS:
         * after MAX_WINDOWS random addresses have been seen, every new client is
         * rejected forever.  Reap idle entries first and, if all entries are still
         * active, evict the least recently used one.  The cap is enforced while the
         * map lock is held, so concurrent first requests cannot grow it past the
         * configured bound.
         */
        Deque<Long> q;
        synchronized (windows) {
            reapExpiredWindows(cutoff);
            q = windows.get(key);
            if (q == null) {
                if (windows.size() >= MAX_WINDOWS) {
                    evictLeastRecentlyUsed();
                }
                q = new ArrayDeque<>();
                windows.put(key, q);
            }
            lastAccess.put(key, now);
        }

        synchronized (q) {
            while (!q.isEmpty() && q.peekFirst() < cutoff) {
                q.pollFirst();
            }
            if (q.size() >= requestLimit) {
                response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
                response.setContentType("application/json;charset=UTF-8");
                response.setHeader("X-RateLimit-Source", "miniagent-api");
                long retryAfterMillis = q.peekFirst() + WINDOW_IDLE_MILLIS - now;
                long retryAfterSeconds = Math.max(1L,
                        (retryAfterMillis + 999L) / 1_000L);
                response.setHeader("Retry-After", Long.toString(retryAfterSeconds));
                response.getWriter().write("{\"error\":\"rate limit exceeded\"}");
                return;
            }
            q.addLast(now);
        }
        filterChain.doFilter(request, response);
    }

    /** Remove windows that cannot contribute to the current one-minute window. */
    private void reapExpiredWindows(long cutoff) {
        Iterator<Map.Entry<String, Long>> iterator = lastAccess.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Long> entry = iterator.next();
            if (entry.getValue() >= cutoff) {
                // LinkedHashMap is ordered by last access, so all remaining
                // entries are newer than this one.
                break;
            }
            String key = entry.getKey();
            Deque<Long> q = windows.get(key);
            if (q == null) {
                iterator.remove();
                continue;
            }
            synchronized (q) {
                while (!q.isEmpty() && q.peekFirst() < cutoff) {
                    q.pollFirst();
                }
                if (q.isEmpty() && windows.remove(key, q)) {
                    iterator.remove();
                }
            }
        }
    }

    /** Evict the oldest entry when all known clients are currently active. */
    private void evictLeastRecentlyUsed() {
        String oldestKey = null;
        Iterator<String> iterator = lastAccess.keySet().iterator();
        if (iterator.hasNext()) {
            oldestKey = iterator.next();
            iterator.remove();
        }
        if (oldestKey != null) {
            Deque<Long> removed = windows.remove(oldestKey);
            if (removed != null) {
                synchronized (removed) {
                    removed.clear();
                }
            }
        }
    }

    private boolean isBackgroundRead(HttpServletRequest request) {
        if (!"GET".equalsIgnoreCase(request.getMethod())) return false;
        String path = applicationPath(request);
        for (String pattern : BACKGROUND_READ_PATHS) {
            if (PATH_MATCHER.match(pattern, path)) return true;
        }
        return false;
    }

    private String rateKey(HttpServletRequest request, boolean backgroundRead) {
        Long uid = jwtSessionService.resolveUserIdAndRefresh(request);
        String bucket = backgroundRead ? "background-read:" : "request:";
        if (uid != null) {
            return bucket + "u:" + uid;
        }
        String ip = request.getRemoteAddr();
        return bucket + "ip:" + (ip == null ? "unknown" : ip);
    }
}
