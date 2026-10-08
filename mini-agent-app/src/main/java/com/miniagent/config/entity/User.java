package com.miniagent.config.entity;

import jakarta.persistence.*;
import lombok.Data;

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

}
