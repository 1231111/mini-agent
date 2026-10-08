package com.miniagent.account.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
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
 *   <li><b>不映射任何会话 / 配额字段。</b>本服务不签 token（没有会话），
 *       配额上限存在 {@link Tenant#getDailyTokenLimit()} 上而不是用户上。</li>
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
}
