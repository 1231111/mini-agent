package com.miniagent.memory.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * App 与运维调用 memory-service 的 ES256 服务令牌。评估回放在记忆服务进程内，不再单独签发。
 *
 * <p>主体（租户、用户、项目）只从令牌里取，请求体里的同名字段不被信任。
 * {@code kid} 头等于签发方，服务端按签发方查公钥（轮换期可并存多把）。</p>
 */
public final class ServiceToken {
    public static final String HEADER = "Authorization";
    public static final String BEARER = "Bearer ";
    public static final String AUDIENCE = "memory-service";
    public static final String ROLE_APP = "app";
    public static final String ROLE_ADMIN = "admin";
    public static final String ROLE_EVAL = "eval";

    private static final String CLAIM_ROLE = "role";
    private static final String CLAIM_TENANT = "tid";
    private static final String CLAIM_PROJECT = "pid";
    private static final long CLOCK_SKEW_SECONDS = 30;
    private static final String KEY_ALGORITHM = "EC";
    private static final ObjectMapper JSON = new ObjectMapper();

    private ServiceToken() {
    }

    /** 令牌主体。userId / tenantId 为空表示系统调用（outbox、评估 worker）。 */
    public record Principal(String issuer, String role, String tenantId, Long userId,
                            String projectId) {
        public boolean hasUser() {
            return userId != null && tenantId != null && !tenantId.isBlank();
        }
    }

    public static String sign(PrivateKey key, Principal principal, Duration ttl) {
        Instant now = Instant.now();
        JwtBuilder builder = Jwts.builder()
                .header().keyId(principal.issuer()).and()
                .issuer(principal.issuer())
                .audience().add(AUDIENCE).and()
                .claim(CLAIM_ROLE, principal.role())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .id(UUID.randomUUID().toString());
        if (principal.userId() != null) {
            builder.subject(principal.userId().toString());
        }
        if (principal.tenantId() != null) {
            builder.claim(CLAIM_TENANT, principal.tenantId());
        }
        if (principal.projectId() != null && !principal.projectId().isBlank()) {
            builder.claim(CLAIM_PROJECT, principal.projectId());
        }
        return builder.signWith(key, Jwts.SIG.ES256).compact();
    }

    /**
     * 校验签名、受众、签发方与有效期。
     *
     * @throws IllegalArgumentException 任何校验失败（调用方统一转 401）
     */
    public static Principal verify(String token, Map<String, List<PublicKey>> keysByIssuer) {
        String issuer = keyId(token);
        for (PublicKey key : keysByIssuer.getOrDefault(issuer, List.of())) {
            try {
                Claims claims = Jwts.parser().verifyWith(key)
                        .requireAudience(AUDIENCE).requireIssuer(issuer)
                        .clockSkewSeconds(CLOCK_SKEW_SECONDS)
                        .build().parseSignedClaims(token).getPayload();
                String subject = claims.getSubject();
                return new Principal(issuer, claims.get(CLAIM_ROLE, String.class),
                        claims.get(CLAIM_TENANT, String.class),
                        subject == null ? null : Long.valueOf(subject),
                        claims.get(CLAIM_PROJECT, String.class));
            } catch (JwtException | NumberFormatException e) {
                // 轮换期并存多把公钥：换下一把再试
            }
        }
        throw new IllegalArgumentException("invalid service token");
    }

    /** PKCS#8 DER 的 Base64，或带头尾行的 PEM。 */
    public static PrivateKey privateKey(String encoded) {
        try {
            return KeyFactory.getInstance(KEY_ALGORITHM)
                    .generatePrivate(new PKCS8EncodedKeySpec(decode(encoded)));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("invalid EC private key (expect PKCS#8 base64)", e);
        }
    }

    /** X.509 SubjectPublicKeyInfo DER 的 Base64，或带头尾行的 PEM。 */
    public static PublicKey publicKey(String encoded) {
        try {
            return KeyFactory.getInstance(KEY_ALGORITHM)
                    .generatePublic(new X509EncodedKeySpec(decode(encoded)));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("invalid EC public key (expect X.509 base64)", e);
        }
    }

    private static byte[] decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            throw new IllegalArgumentException("key is empty");
        }
        String body = encoded.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
        return Base64.getDecoder().decode(body);
    }

    private static String keyId(String token) {
        int dot = token == null ? -1 : token.indexOf('.');
        if (dot <= 0) {
            throw new IllegalArgumentException("malformed service token");
        }
        try {
            byte[] header = Base64.getUrlDecoder().decode(token.substring(0, dot));
            JsonNode kid = JSON.readTree(new String(header, StandardCharsets.UTF_8)).get("kid");
            if (kid == null || kid.asText().isBlank()) {
                throw new IllegalArgumentException("service token has no kid");
            }
            return kid.asText();
        } catch (java.io.IOException | IllegalArgumentException e) {
            throw new IllegalArgumentException("malformed service token", e);
        }
    }
}
