package com.miniagent.account.service;

import com.miniagent.account.entity.User;
import com.miniagent.account.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;

/**
 * 客户机服务令牌：登录时签发，之后每次调会员接口带着它。
 *
 * <h2>为什么需要它</h2>
 *
 * <p>账号服务不签发也不校验用户的 JWT —— 那是客户机后端的事。所以它拿到
 * {@code userId=65} 时无法自己判断真假，只能信任调用方。原来的信任凭证是一把
 * 全局共享的 {@code internal-key}，但它随客户端分发到用户机器上，
 * 谁提取出来都能写任意用户的用量。</p>
 *
 * <p>换成每用户一把之后，令牌与 userId 绑死：请求里出现别人的 userId 直接拒绝。</p>
 *
 * <h2>为什么是不透明串而不是 JWT</h2>
 *
 * <p>JWT 要么无法撤销（必须等过期），要么撤销就得维护黑名单——那还是要一张表。
 * 不透明串查库一次就知道是否有效，撤销就是清空哈希，且不需要管理签名密钥
 * （密钥泄露等于能伪造任意用户，风险比一张表大得多）。</p>
 *
 * <h2>单设备</h2>
 *
 * <p>同一用户同时只有一把：{@link #rotate} 会覆盖上一次的值。第二台设备登录会让
 * 第一台的令牌失效，表现为"需要重新登录"。这是刻意的——多设备并存需要按设备维度
 * 存多行，而当前产品形态是一人一台客户端。</p>
 */
@Service
public class AgentTokenService {

    private static final Logger log = LoggerFactory.getLogger(AgentTokenService.class);

    /** 前缀让令牌在日志和配置里一眼可辨，也避免和 JWT（eyJ...）混在一起。 */
    static final String PREFIX = "atk_";
    /** 90 天。到期需重新登录；在此之前只要不再登录就一直有效。 */
    static final Duration TTL = Duration.ofDays(90);
    private static final int SECRET_BYTES = 32;

    private final SecureRandom random = new SecureRandom();

    @Autowired
    private UserRepository users;

    public record Issued(String token, LocalDateTime expiresAt) {
    }

    /**
     * 签发新令牌并作废该用户之前那一把。
     *
     * <p>只在登录成功时调用。调用方拿到的 {@code token} 是唯一一次能看到原文的机会，
     * 之后库里只有哈希。</p>
     */
    @Transactional
    public Issued rotate(User user) {
        String token = PREFIX + randomToken();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiresAt = now.plus(TTL);

        user.setAgentTokenHash(sha256Hex(token));
        user.setAgentTokenIssuedAt(now);
        user.setAgentTokenExpiresAt(expiresAt);
        users.save(user);

        log.info("签发客户机令牌: userId={} expiresAt={}", user.getId(), expiresAt);
        return new Issued(token, expiresAt);
    }

    /**
     * 解析令牌，返回它绑定的 userId。
     *
     * @return null 表示令牌无效、已过期、或用户已被禁用 —— 调用方一律按未认证处理，
     *         不区分这三种情况：区分开等于告诉攻击者"这个令牌曾经存在过"
     */
    @Transactional(readOnly = true)
    public Long resolve(String token) {
        if (token == null || token.isBlank() || !token.startsWith(PREFIX)) return null;
        return users.findByAgentTokenHash(sha256Hex(token))
                .filter(User::isEnabled)
                .filter(user -> {
                    LocalDateTime expiresAt = user.getAgentTokenExpiresAt();
                    // 没有过期时间的旧行不认：宁可让人重新登录，也不要留一把永不过期的凭证。
                    return expiresAt != null && expiresAt.isAfter(LocalDateTime.now());
                })
                .map(User::getId)
                .orElse(null);
    }

    /** 主动撤销（封号、改密码、或用户手动登出全部设备）。 */
    @Transactional
    public void revoke(Long userId) {
        users.findById(userId).ifPresent(user -> {
            user.setAgentTokenHash(null);
            user.setAgentTokenIssuedAt(null);
            user.setAgentTokenExpiresAt(null);
            users.save(user);
        });
    }

    private String randomToken() {
        byte[] bytes = new byte[SECRET_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                out.append(Character.forDigit((b >> 4) & 0xF, 16));
                out.append(Character.forDigit(b & 0xF, 16));
            }
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
