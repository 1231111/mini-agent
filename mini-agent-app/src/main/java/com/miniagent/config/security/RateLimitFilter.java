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
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
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
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        // 放行 OPTIONS 预检请求，避免干扰 CORS
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        return path.startsWith("/actuator")
                || path.startsWith("/css")
                || path.startsWith("/js");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String key = rateKey(request);
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

    private String rateKey(HttpServletRequest request) {
        Long uid = jwtSessionService.resolveUserIdAndRefresh(request);
        if (uid != null) {
            return "u:" + uid;
        }
        String ip = request.getRemoteAddr();
        return "ip:" + (ip == null ? "unknown" : ip);
    }
}
