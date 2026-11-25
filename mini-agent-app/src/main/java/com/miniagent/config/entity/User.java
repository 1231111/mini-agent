package com.miniagent.config.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Entity
@Data
@Table(name = "users", uniqueConstraints = @UniqueConstraint(name = "ux_users_external_id", columnNames = "external_id"))
public class User extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String username;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "display_name", length = 100)
    private String displayName;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    /** Explicit administrator override for this user's daily token limit; null inherits membership/default. */
    @Column(name = "daily_token_limit_override")
    private Long dailyTokenLimitOverride;

    /** Per-user daily allowance synchronized from the account membership service. */
    @Column(name = "membership_daily_token_limit")
    private Long membershipDailyTokenLimit;

    @Column(name = "membership_max_concurrent_tasks")
    private Integer membershipMaxConcurrentTasks;

    /** Membership expiry; null means the membership allowance is perpetual. */
    @Column(name = "membership_expires_at")
    private java.time.LocalDateTime membershipExpiresAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private UserRole role = UserRole.USER;

    @Column(nullable = false)
    private boolean enabled = true;

    /**
     * 云端身份的映射键（{@code agent.auth.cloud.base-url} 指向的账号服务里那个用户的 id）。
     *
     * <p>为 null 表示这是一条纯本地账号。非 null 说明本行是云端用户在<b>本机</b>的
     * <b>影子记录</b>：账号的真实归属与密码都在云端，本地这行只承担两件事：
     * <ul>
     *   <li>{@code SignedSessionFilter.resolvePrincipal(userId)} 能查到人 ——
     *       它每次请求都要 {@code userRepository.findById}，本地没有这行就一律 401，
     *       无论 token 本身多合法；</li>
     *   <li>本地业务表（{@code chat_conversations.user_id}、{@code agent_memory_entries} 等）
     *       的外键有落点。</li>
     * </ul>
     *
     * <p>为什么用云端 id 而不是直接复用本地 id：两边 id 空间无关，
     * 直接复用会在"本地先建了 id=1、云端 id=1 是另一个人"时把两条身份悄悄并成一条。
     * 映射键必须是外部 id。
     *
     * <p>长度 128 是刻意放宽的：云端 id 可能是 UUID、雪花号或带前缀的字符串，
     * 这里不应该对它的形状做任何假设。
     */
    @Column(name = "external_id", length = 128)
    private String externalId;

    /**
     * 云端签发给本机的服务令牌（{@code atk_...}）。只在登录成功时写入，
     * 之后调会员接口带 {@code X-Agent-Token}。
     *
     * <p>老版本服务端不返回该值时为 null，此时调用方退回全局 internal-key ——
     * 这是升级顺序不一致时的兼容路径，不是正常状态。</p>
     */
    @Column(name = "cloud_agent_token", length = 128)
    private String cloudAgentToken;

    @Column(name = "cloud_agent_token_expires_at")
    private LocalDateTime cloudAgentTokenExpiresAt;

    @Column(name = "cloud_tenant_id", length = 128)
    private String cloudTenantId;

    @Column(name = "memory_access_token", length = 4096)
    private String memoryAccessToken;

    @Column(name = "memory_refresh_credential", length = 128)
    private String memoryRefreshCredential;

}
