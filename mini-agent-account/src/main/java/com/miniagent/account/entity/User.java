package com.miniagent.account.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 账号本体。
 *
 * <p>与云端 agent 里那个同名实体有两处刻意的差异，都是因为"同一个表、两种身份"：
 *
 * <ol>
 *   <li><b>不映射 {@code external_id}。</b>那一列是给客户机用的 —— 它记的是
 *       "本地这一行对应云端哪个人"。在账号服务里 {@code id} 本身就是云端身份，
 *       没有"映射到别处"这回事。字段不映射不影响表结构：{@code ddl-auto: validate}
 *       只校验"实体的字段在表里存在"，不要求表里没有多余的列。</li>
 *   <li><b>只映射会员权益快照，不映射本地会话或管理员覆盖。</b>账号服务不签 agent token；
 *       但共享库中的会员 token 与并发权益要在充值事务中写给 agent 执行侧读取。</li>
 * </ol>
 *
 * <p>{@code users.tenant_id} 是 {@code NOT NULL} 且有外键指向 tenants，
 * 所以注册流程必须先落 tenant 再落 user —— 这不是可选的顺序。
 */
@Entity
@Data
@Table(name = "users")
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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private UserRole role = UserRole.USER;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "memory_refresh_token_hash", length = 64, unique = true)
    private String memoryRefreshTokenHash;

    @Column(name = "membership_daily_token_limit")
    private Long membershipDailyTokenLimit;

    @Column(name = "membership_max_concurrent_tasks")
    private Integer membershipMaxConcurrentTasks;

    @Column(name = "membership_expires_at")
    private LocalDateTime membershipExpiresAt;

    /**
     * 客户机服务令牌的 SHA-256 十六进制值。
     *
     * <p>同一用户同时只有一把：重新登录即轮换，旧值立即失效（单设备语义）。
     * 库里只存哈希，和 {@code memory_refresh_token_hash} 同一个处理方式 ——
     * 库被读走也拿不到可用于调用的原文。</p>
     */
    @Column(name = "agent_token_hash", length = 64)
    private String agentTokenHash;

    @Column(name = "agent_token_issued_at")
    private LocalDateTime agentTokenIssuedAt;

    @Column(name = "agent_token_expires_at")
    private LocalDateTime agentTokenExpiresAt;

}
