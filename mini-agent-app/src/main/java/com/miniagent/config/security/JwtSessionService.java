package com.miniagent.config.security;

import com.miniagent.agent.core.SessionEventCenter;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;
import java.util.Objects;
import java.util.UUID;

/**
 * JWT session with server-side sliding expiry.
 *
 * <p>凭证只走 {@code Authorization: Bearer} 头，服务端不写任何 cookie：
 * <ul>
 *   <li>JWT 用 HS256 签名，密钥来自 {@code agent.auth.jwt-secret}；{@code exp} 只是硬上限
 *       （{@code agent.auth.jwt-exp-seconds}，默认 7 天），不承担会话时长语义。</li>
 *   <li>真实会话时长由服务端那份会话记录决定（{@code agent.auth.jwt-ttl-seconds}，
 *       默认 30 分钟空闲超时）。每次鉴权成功的请求都会续期，因此
 *       「空闲超时就过期」在 JWT 本身还没到期时同样生效。</li>
 *   <li>退出登录立刻吊销该会话，无论 JWT 是否到期。</li>
 * </ul>
 *
 * <p>会话记录落在哪里由 {@link SessionStore} 决定（Redis 或数据库），本类不再直接
 * 依赖 Redis —— 桌面客户端档没有 Redis，原来的 {@code @Autowired StringRedisTemplate}
 * 会让登录路径直接抛 {@code RedisConnectionFailureException} 冒泡成 500。
 * 选哪个后端见 {@link SessionStore} 的说明。
 *
 * <p>本次改造移除了三条旧通路：{@code ma_token} httpOnly 兜底 cookie、
 * {@code access_token} 查询参数、{@code XSRF-TOKEN} CSRF cookie（CSRF 防护随之整体关闭）。
 * 三者都是「浏览器自动携带凭证」的形态，只在凭证能被自动携带时 CSRF 才成立；
 * 现在 token 由前端存 sessionStorage 并显式放进请求头，跨站请求无法自动附带它。
 * 代价要写清楚：sessionStorage 对 XSS 是可读的，而 httpOnly cookie 不可读 ——
 * 这是把「防 XSS 窃取」换成了「防 CSRF」，不是单纯的增强。
 */
@Service
public class JwtSessionService {

    private static final Logger log = LoggerFactory.getLogger(JwtSessionService.class);

    public static final String ATTR_USER_ID = "authUserId";
    /** Request attribute carrying the verified user/tenant/role principal. */
    public static final String ATTR_PRINCIPAL = "authPrincipal";

    public static final String AUTH_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    @Value("${agent.auth.jwt-secret}")
    private String jwtSecret;
    @Value("${agent.auth.jwt-exp-seconds:604800}")
    private int jwtExpSeconds;
    @Value("${agent.auth.jwt-ttl-seconds:1800}")
    private int jwtTtlSeconds;

    /**
     * 会话记录存储。后端由 {@code agent.replica.mode} 决定，这里不关心具体是哪个 ——
     * 也刻意不在这里判断「redis 是不是 null」，理由见 {@link SessionStore}。
     */
    @Autowired
    private SessionStore sessionStore;

    /**
     * 可选依赖：登出要把该用户已建立的 SSE 长连接一并摘掉。
     * 不摘的话，退登只吊销了服务端会话，页面那条已经建好的事件流仍然活着，
     * 会继续把该会话的推送送给一个已退登的窗口。
     */
    @Autowired(required = false)
    private SessionEventCenter eventCenter;

    private javax.crypto.SecretKey key;

    @PostConstruct
    private void init() {
        if (jwtSecret == null || jwtSecret.isBlank()) {
            throw new IllegalStateException(
                    "agent.auth.jwt-secret must be set (env JWT_SECRET；旧名 COOKIE_SECRET 仍可回退)");
        }
        if (jwtSecret.getBytes(StandardCharsets.UTF_8).length * 8 < 256) {
            throw new IllegalStateException(
                    "agent.auth.jwt-secret must be at least 256 bits for HS256; "
                            + "current length=" + jwtSecret.length() + " chars");
        }
        this.key = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
        // 客户机上「登录一会儿就掉线」这类问题的第一个排查点：会话到底记在哪。
        // SessionStore 的两个实现各自也会打一行，这行打的是实际注入进来的那个。
        log.info("会话存储后端: {}（空闲超时 {} 秒，JWT exp 上限 {} 秒）",
                sessionStore.backendName(), jwtTtlSeconds, jwtExpSeconds);
    }

    /**
     * 签发 JWT 并登记服务端会话。返回值由调用方放进响应体，交给前端存 sessionStorage；
     * 服务端不再写 cookie，所以这里不需要 HttpServletResponse。
     */
    public String issueToken(Long userId) {
        String jti = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        String token = Jwts.builder()
                .id(jti)
                .subject(String.valueOf(userId))
                .issuedAt(new Date(now))
                .expiration(new Date(now + (long) jwtExpSeconds * 1000L))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
        sessionStore.create(token, userId, Duration.ofSeconds(jwtTtlSeconds));
        return token;
    }

    /**
     * Resolve the user id from a valid, unexpired, non-revoked token.
     * Refreshes the server-side sliding expiry on every successful resolution.
     * Returns null when the token is missing, invalid, expired, or revoked.
     */
    public Long resolveUserIdAndRefresh(HttpServletRequest request) {
        String token = extractBearerToken(request);
        if (token == null) {
            return null;
        }
        try {
            // 先验签 + 验 exp，挡住明显伪造/过期的 token，避免每次都去查存储。
            Jws<Claims> jws = Jwts.parser().verifyWith(key).build().parseSignedClaims(token);
            if (jws.getPayload() == null) {
                return null;
            }
            // 会话是否仍然有效（未登出、未空闲超时）由存储说了算。
            // 返回的 userId 取自会话记录本身，而不是 token 里的 subject ——
            // 吊销/续期的真相源是这份记录，不是客户端拿着的那个字符串。
            return sessionStore.validateAndTouch(token, Duration.ofSeconds(jwtTtlSeconds));
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }

    /** Log out: revoke the server-side session and detach this user's SSE streams. */
    public void logout(HttpServletRequest request) {
        String token = extractBearerToken(request);
        if (token != null) {
            try {
                Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
                sessionStore.revoke(token);
                detachUserStreams(Long.parseLong(claims.getSubject()));
            } catch (JwtException | IllegalArgumentException ignored) {
                // nothing to revoke if the token itself is invalid
            }
        }
    }

    /** 摘掉该用户挂在事件中枢上的所有 SSE 连接。摘流失败不影响登出本身。 */
    private void detachUserStreams(Long userId) {
        if (Objects.isNull(eventCenter)) {
            return;
        }
        try {
            eventCenter.detachUser(userId);
        } catch (Exception e) {
            log.warn("登出摘流失败 userId={}", userId, e);
        }
    }

    /** Already-authenticated user id placed on the request by the security filter. */
    public static Long userIdFromRequest(HttpServletRequest request) {
        Object v = request.getAttribute(ATTR_USER_ID);
        if (v instanceof Long id) {
            return id;
        }
        return null;
    }

    /**
     * 只认 {@code Authorization: Bearer} 头。
     * cookie 与 {@code access_token} 查询参数两条通路已随无状态改造移除 ——
     * 保留它们等于让凭证可以脱离 JS 显式设置而被自动携带，CSRF 防护刚关掉，不能再留这种口子。
     */
    private static String extractBearerToken(HttpServletRequest request) {
        String auth = request.getHeader(AUTH_HEADER);
        if (auth == null || !auth.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String t = auth.substring(BEARER_PREFIX.length()).trim();
        return t.isEmpty() ? null : t;
    }
}
