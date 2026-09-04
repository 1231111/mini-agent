package com.miniagent.config.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;
import java.util.UUID;

/**
 * JWT-based session with Redis-controlled sliding expiry.
 *
 * <p>认证从「签名 cookie」迁移到「JWT（Bearer 头 + httpOnly cookie 兜底）」：
 * <ul>
 *   <li>JWT 用 HS256 签名，密钥复用 {@code agent.auth.cookie-secret}；{@code exp} 仅是硬性上限
 *       （agent.auth.jwt-exp-seconds，默认 7 天），纯兜底。</li>
 *   <li>真实会话时长由 Redis 键 {@code session:jwt:{jti}} 的滑动 TTL 决定
 *       （agent.auth.jwt-ttl-seconds，默认 30 分钟）。每次鉴权成功的请求都会刷新该 TTL，
 *       因此「空闲超时就过期」即使 JWT 本身还没到期也会生效。</li>
 *   <li>退出登录即删除 Redis 键，无论 JWT 是否到期都立即吊销。</li>
 *   <li>传输方式：{@code Authorization: Bearer} 头优先；httpOnly 的 {@code ma_token} cookie 作为
 *       整页跳转 / GET 链接的兜底；{@code access_token} 查询参数用于 EventSource（无法自定义头）。</li>
 * </ul>
 */
@Service
public class JwtSessionService {

    public static final String ATTR_USER_ID = "authUserId";
    /** Request attribute carrying the verified user/tenant/role principal. */
    public static final String ATTR_PRINCIPAL = "authPrincipal";

    public static final String COOKIE_NAME = "ma_token";
    public static final String AUTH_HEADER = "Authorization";
    public static final String TOKEN_PARAM = "access_token";
    private static final String REDIS_PREFIX = "session:jwt:";
    // 与 Spring CookieCsrfTokenRepository.DEFAULT_CSRF_COOKIE_NAME 保持一致
    private static final String CSRF_COOKIE_NAME = "XSRF-TOKEN";

    @Value("${agent.auth.cookie-secret}")
    private String cookieSecret;
    @Value("${agent.auth.jwt-exp-seconds:604800}")
    private int jwtExpSeconds;
    @Value("${agent.auth.jwt-ttl-seconds:1800}")
    private int jwtTtlSeconds;
    @Value("${agent.auth.secure-cookie:false}")
    private boolean secureCookie;
    @Value("${agent.auth.same-site:Lax}")
    private String sameSite;

    @Autowired
    private StringRedisTemplate redis;

    private javax.crypto.SecretKey key;

    @PostConstruct
    private void init() {
        if (cookieSecret == null || cookieSecret.isBlank()) {
            throw new IllegalStateException("agent.auth.cookie-secret must be set");
        }
        if (cookieSecret.getBytes(StandardCharsets.UTF_8).length * 8 < 256) {
            throw new IllegalStateException(
                    "agent.auth.cookie-secret must be at least 256 bits for HS256; "
                            + "current length=" + cookieSecret.length() + " chars");
        }
        this.key = Keys.hmacShaKeyFor(cookieSecret.getBytes(StandardCharsets.UTF_8));
    }

    /** Issue a JWT, persist the sliding session in Redis, and write the httpOnly fallback cookie. */
    public String issueToken(HttpServletResponse response, Long userId, String username) {
        String jti = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        String token = Jwts.builder()
                .id(jti)
                .subject(String.valueOf(userId))
                .issuedAt(new Date(now))
                .expiration(new Date(now + (long) jwtExpSeconds * 1000L))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
        redis.opsForValue().set(redisKey(jti), String.valueOf(userId), Duration.ofSeconds(jwtTtlSeconds));
        writeCookie(response, token);
        return token;
    }

    /**
     * Resolve the user id from a valid, unexpired, non-revoked token.
     * Refreshes the Redis sliding TTL on every successful resolution.
     * Returns null when the token is missing, invalid, expired, or revoked.
     */
    public Long resolveUserIdAndRefresh(HttpServletRequest request) {
        String token = extractToken(request);
        if (token == null) {
            return null;
        }
        try {
            Jws<Claims> jws = Jwts.parser().verifyWith(key).build().parseSignedClaims(token);
            Claims claims = jws.getPayload();
            String redisKey = redisKey(claims.getId());
            // Revoked (logout) or idle-expired (Redis TTL elapsed)?
            if (Boolean.FALSE.equals(redis.hasKey(redisKey))) {
                return null;
            }
            // Sliding window: bump TTL on every authenticated request.
            redis.expire(redisKey, Duration.ofSeconds(jwtTtlSeconds));
            return Long.parseLong(claims.getSubject());
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }

    /** Log out: revoke the Redis session key and clear the fallback cookie. */
    public void logout(HttpServletRequest request, HttpServletResponse response) {
        String token = extractToken(request);
        if (token != null) {
            try {
                Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
                redis.delete(redisKey(claims.getId()));
            } catch (JwtException | IllegalArgumentException ignored) {
                // nothing to revoke if the token itself is invalid
            }
        }
        clearCookie(response);
        // 退出时一并清理 CSRF cookie，避免下次登录带“旧”的 XSRF-TOKEN
        clearCsrfCookie(response);
    }

    /** Already-authenticated user id placed on the request by the security filter. */
    public static Long userIdFromRequest(HttpServletRequest request) {
        Object v = request.getAttribute(ATTR_USER_ID);
        if (v instanceof Long id) {
            return id;
        }
        return null;
    }

    private String extractToken(HttpServletRequest request) {
        String auth = request.getHeader(AUTH_HEADER);
        if (auth != null && auth.length() > 7 && auth.startsWith("Bearer ")) {
            String t = auth.substring(7).trim();
            if (!t.isEmpty()) {
                return t;
            }
        }
        Cookie c = findCookie(request, COOKIE_NAME);
        if (c != null && c.getValue() != null && !c.getValue().isEmpty()) {
            return c.getValue();
        }
        String param = request.getParameter(TOKEN_PARAM);
        if (param != null && !param.isEmpty()) {
            return param;
        }
        return null;
    }

    private static String redisKey(String jti) {
        return REDIS_PREFIX + jti;
    }

    private void writeCookie(HttpServletResponse response, String token) {
        response.addHeader(HttpHeaders.SET_COOKIE, buildCookie(COOKIE_NAME, token, jwtExpSeconds, true));
    }

    private void clearCookie(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, buildCookie(COOKIE_NAME, "", 0, true));
    }

    private void clearCsrfCookie(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, buildCookie(CSRF_COOKIE_NAME, "", 0, false));
    }

    private String buildCookie(String name, String value, long maxAge, boolean httpOnly) {
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(name, value)
                .path("/")
                .maxAge(maxAge)
                .httpOnly(httpOnly)
                .secure(secureCookie);
        if (sameSite != null) {
            builder.sameSite(sameSite.trim());
        }
        return builder.build().toString();
    }

    private static Cookie findCookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie c : cookies) {
            if (name.equals(c.getName())) {
                return c;
            }
        }
        return null;
    }
}
