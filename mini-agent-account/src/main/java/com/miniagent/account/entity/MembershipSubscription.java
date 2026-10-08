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

import java.time.LocalDateTime;

/**
 * 用户当前的订阅。
 *
 * <p>同时冗余 {@code tenant_id}：查"这个租户该配多少额度"时不必回表 users，
 * 而那次查询发生在订阅生效的写路径上 —— 少一次 join，也少一次"tenant 到底在 users 上还是在这里"的歧义。
 *
 * <p>一个用户同时只应有一条 {@code ACTIVE} 订阅。这个约束<b>不在数据库上</b>
 * （MySQL 没有部分唯一索引），由 {@link com.miniagent.account.service.MembershipService}
 * 在事务内把旧订阅置为 {@code SUPERSEDED} 再插新的来保证。
 */
@Entity
@Data
@Table(name = "membership_subscriptions")
public class MembershipSubscription extends BaseEntity {

    public enum Status {
        /** 生效中。 */
        ACTIVE,
        /** 被新订阅替换掉了（升级/续费）。保留历史，不删。 */
        SUPERSEDED,
        /** 到期。 */
        EXPIRED,
        /** 用户取消。到期时间未到前仍可继续用，具体语义由查询侧决定。 */
        CANCELED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    /** 记 code 而不是 plan 主键：主键会随重建而变，code 是稳定标识。 */
    @Column(name = "plan_code", nullable = false, length = 32)
    private String planCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status = Status.ACTIVE;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    /** null 表示永久（free 档就是这样）。 */
    @Column(name = "expire_at")
    private LocalDateTime expireAt;

    @Column(name = "source_order_no", length = 64)
    private String sourceOrderNo;
}
