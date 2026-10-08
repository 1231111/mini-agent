package com.miniagent.account.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 租户。它同时承担两个角色，这是刻意复用的：
 *
 * <ol>
 *   <li><b>账号的归属单元</b> —— 每个用户注册时得到自己的一条 tenant，
 *       slug 形如 {@code u-<userId>}。原先 {@code AuthService} 把所有人塞进同一个
 *       {@code system} 租户，等于所有用户共用一个配额池，会员分档无从谈起。</li>
 *   <li><b>配额的载体</b> —— {@code daily_token_limit} 是云端 agent 侧
 *       {@code DbTenantTokenQuota.check/consume(tenantId)} 唯一读的字段。</li>
 * </ol>
 *
 * <p>把会员等级翻译成"写这个字段"，好处是云端 agent 完全不需要知道"会员"这个概念：
 * {@code DbTenantTokenQuota} 一行都不用改。会员的语义留在本服务里，
 * agent 只看到一个普通的配额上限。
 *
 * <p>{@code daily_token_limit <= 0} 表示不限量（沿用云端 agent 侧的既有语义）。
 */
@Entity
@Table(name = "tenants")
public class Tenant extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String slug;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "daily_token_limit", nullable = false)
    private long dailyTokenLimit;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public long getDailyTokenLimit() { return dailyTokenLimit; }
    public void setDailyTokenLimit(long dailyTokenLimit) { this.dailyTokenLimit = dailyTokenLimit; }
}
