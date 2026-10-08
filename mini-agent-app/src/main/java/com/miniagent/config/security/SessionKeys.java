package com.miniagent.config.security;

import java.util.HexFormat;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 会话键的派生：把不透明的凭证（JWT 原文）压成固定长度的十六进制摘要。
 *
 * <p>两个后端共用同一条派生规则，所以 {@code SessionStore} 的方法签名可以只收
 * 「token 原文」这一件事，不需要知道 JWT 内部长什么样：
 * <ul>
 *   <li>{@link DbSessionStore} —— 摘要写进 {@code auth_sessions.token_hash}，
 *       正好对上这一列原本的定义（只落 SHA-256，原文不落库）。</li>
 *   <li>{@link RedisSessionStore} —— 摘要拼进键 {@code session:jwt:{digest}}，
 *       Redis 里同样不出现 token 原文。</li>
 * </ul>
 *
 * <p>用摘要而不是直接用 token 原文当键，有三个后果值得记住：键长度恒定（不受
 * claims 多少影响）、Redis/数据库里留不下可直接重放的凭证、运维侧看键值也看不出
 * 是哪个用户 —— 后者是刻意的。
 *
 * <p>项目里 {@code ToolPipeline} 与 {@code TodoSemanticValidator} 各自内联过同样的
 * SHA-256 逻辑。这里没有跟着内联，是因为它有第二个调用方（两个 Store），
 * 抄两遍就等于把「摘要算法要一致」变成一句口头约定。
 */
final class SessionKeys {

    private SessionKeys() {
    }

    /** 返回 token 的 SHA-256，64 位小写十六进制。 */
    static String digest(String token) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必须提供的算法，走不到这里；真走到了说明运行环境已经不正常。
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
