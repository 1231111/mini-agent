package com.miniagent.account.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.account.entity.User;
import com.miniagent.memory.contract.MemoryAccessToken;
import com.miniagent.account.repository.UserRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;

/** Account-signed opaque credential for memory-service, including a bounded offline lease. */
@Service
public class MemoryAccessTokenService {
    private static final Logger log = LoggerFactory.getLogger(MemoryAccessTokenService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int REFRESH_CREDENTIAL_BYTES = 32;

    private final UserRepository users;
    private final ObjectMapper json;
    private final Clock clock;
    private final String keyMaterial;
    private final String internalKey;

    public MemoryAccessTokenService(UserRepository users, ObjectMapper json, Clock clock,
                                    @Value("${agent.account.memory-token-key:}") String keyMaterial,
                                    @Value("${agent.account.internal-key:}") String internalKey) {
        this.users = users;
        this.json = json;
        this.clock = clock;
        this.keyMaterial = keyMaterial == null ? "" : keyMaterial.trim();
        this.internalKey = internalKey == null ? "" : internalKey.trim();
    }

    /**
     * 启动即校验密钥，而不是等到用户点登录才炸。
     *
     * <p>密钥缺失时 {@link #issue} 会抛 {@link IllegalStateException}，被全局异常处理器包成
     * {@code SYSTEM.01.01}，运维看到的是"系统内部错误"，而真正原因是环境变量没配。
     * 这里把它提前到容器启动阶段，日志里直接给出该设置什么。</p>
     */
    @PostConstruct
    void validateConfiguration() {
        if (keyMaterial.isBlank()) {
            throw new IllegalStateException(
                    "agent.account.memory-token-key 未配置：请在部署环境中设置 ACCOUNT_MEMORY_TOKEN_KEY"
                            + "（Base64 编码、解码后正好 32 字节），生成方式：openssl rand -base64 32。"
                            + "未配置时登录会因无法签发记忆访问令牌而返回系统内部错误。");
        }
        try {
            MemoryAccessToken.decodeKey(keyMaterial);
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "agent.account.memory-token-key 格式不合法（" + e.getMessage()
                            + "）：ACCOUNT_MEMORY_TOKEN_KEY 必须是 Base64 编码、解码后正好 32 字节，"
                            + "生成方式：openssl rand -base64 32。", e);
        }
        if (internalKey.isBlank()) {
            log.warn("agent.account.internal-key 未配置：memory-service 无法通过 /internal/oauth/introspect "
                    + "在线校验记忆令牌，只能退回离线解密；请设置 ACCOUNT_INTERNAL_KEY 并与 memory-service 保持一致。");
        }
    }

    @Transactional
    public IssuedCredential issue(User user) {
        requireConfigured();
        requireActive(user);
        String refreshCredential = randomCredential();
        user.setMemoryRefreshTokenHash(credentialHash(refreshCredential));
        users.save(user);
        return new IssuedCredential(user, issueAccessToken(user), refreshCredential);
    }

    @Transactional(readOnly = true)
    public IssuedCredential refresh(String refreshCredential) {
        requireConfigured();
        if (refreshCredential == null || refreshCredential.isBlank()) {
            throw new IllegalArgumentException("invalid memory refresh credential");
        }
        User user = users.findByMemoryRefreshTokenHash(credentialHash(refreshCredential))
                .orElseThrow(() -> new IllegalArgumentException("invalid memory refresh credential"));
        requireActive(user);
        return new IssuedCredential(user, issueAccessToken(user), refreshCredential);
    }

    public record IssuedCredential(User user, String accessToken, String refreshCredential) { }

    public MemoryAccessToken.Claims verifyOffline(String token) {
        return MemoryAccessToken.decrypt(json, encryptionKey(), token, clock.instant());
    }

    @Transactional(readOnly = true)
    public MemoryAccessToken.Claims introspect(String token, String providedInternalKey) {
        requireInternalKey(providedInternalKey);
        MemoryAccessToken.Claims claims = MemoryAccessToken.decrypt(json, encryptionKey(), token, clock.instant());
        User current = users.findById(claims.userId()).orElse(null);
        if (current == null || !current.isEnabled() || current.getTenantId() == null
                || current.getTenantId() != claims.tenantId()) {
            throw new IllegalArgumentException("inactive memory access token");
        }
        return new MemoryAccessToken.Claims(current.getId(), current.getTenantId(),
                current.getUsername(), current.getDisplayName(), current.getRole().name(),
                MemoryAccessToken.AUDIENCE, MemoryAccessToken.PURPOSE,
                claims.issuedAt(), claims.expiresAt(), claims.tokenId());
    }

    private void requireInternalKey(String provided) {
        if (internalKey.isBlank() || provided == null
                || !MessageDigest.isEqual(internalKey.getBytes(StandardCharsets.UTF_8),
                provided.trim().getBytes(StandardCharsets.UTF_8))) {
            throw new SecurityException("invalid service credential");
        }
    }

    private String issueAccessToken(User user) {
        return MemoryAccessToken.issue(json, encryptionKey(), user.getId(), user.getTenantId(),
                user.getUsername(), user.getDisplayName(), user.getRole().name(), clock.instant());
    }

    private static void requireActive(User user) {
        if (user == null || user.getId() == null || user.getTenantId() == null || !user.isEnabled()) {
            throw new IllegalArgumentException("enabled account user is required");
        }
    }

    private static String randomCredential() {
        byte[] bytes = new byte[REFRESH_CREDENTIAL_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String credentialHash(String credential) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(credential.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private byte[] encryptionKey() {
        return MemoryAccessToken.decodeKey(keyMaterial);
    }

    private void requireConfigured() {
        if (keyMaterial.isBlank()) {
            throw new IllegalStateException("agent.account.memory-token-key is not configured");
        }
    }
}
