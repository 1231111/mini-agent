package com.miniagent.account.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * 会员等级定义。做成表而不是枚举，是为了让运营改价改额度不必发版 ——
 * 把"每天 100 万 token、月付 39 元"写进代码常量，每次调价都要走一次发布。
 *
 * <p>{@code dailyTokenLimit} 的单位与 {@code tenants.daily_token_limit} 完全一致，
 * 订阅生效时直接抄过去，中间不做任何换算 —— 有换算就有两套单位，
 * 就会出现"界面显示 100 万，实际只给 1 万"这类要靠对数才能发现的问题。
 */
@Entity
@Data
@Table(name = "membership_plans")
public class MembershipPlan extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 稳定标识：free / pro / team。订阅与订单记的是它，不是主键 —— 主键会随重建而变。 */
    @Column(nullable = false, unique = true, length = 32)
    private String code;

    @Column(nullable = false, length = 64)
    private String name;

    /** 每日 token 上限；{@code <= 0} 表示不限量。 */
    @Column(name = "daily_token_limit", nullable = false)
    private long dailyTokenLimit;

    /**
     * 并发任务上限。字段先落库，但<b>本轮还没有接到</b>云端 agent 的
     * {@code agent.concurrency.max-tasks-per-user}（那边仍是配置里的固定值）。
     * 列在这里是为了让表结构一次到位；接线见 README 的「待办」。
     */
    @Column(name = "max_concurrent_tasks", nullable = false)
    private int maxConcurrentTasks = 2;

    /** 价格，单位"分"。用整数存金额，不用浮点 —— 0.1 + 0.2 那种误差在对账时是灾难。 */
    @Column(name = "price_cents", nullable = false)
    private long priceCents;

    @Column(nullable = false, length = 8)
    private String currency = "CNY";

    /** 一个订阅周期的天数。 */
    @Column(name = "duration_days", nullable = false)
    private int durationDays = 30;

    /** 下架用。已有订阅不受影响，只是不能再新买。 */
    @Column(nullable = false)
    private boolean enabled = true;
}
