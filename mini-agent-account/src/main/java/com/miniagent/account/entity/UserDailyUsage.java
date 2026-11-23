package com.miniagent.account.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDate;

/**
 * 用户在某一天、某个模型上的 token 用量累计。
 *
 * <p>一行 = 「用户 × 日期 × 模型」。同一用户同一天换模型会产生多行，
 * 因为不同模型单价差异大，合并后就无法计价。</p>
 *
 * <p>字段语义：</p>
 * <ul>
 *   <li>{@code inputTokens}：计入输入的 token 总数，含缓存命中部分</li>
 *   <li>{@code cachedInputTokens}：其中命中 prompt cache 的部分，是 input 的子集，不是额外量</li>
 *   <li>{@code outputTokens}：输出 token，含思考内容</li>
 *   <li>{@code totalTokens}：写入方上报的总量；与前两项不等时以本字段为准做账单</li>
 *   <li>{@code costMicros}：百万分之一元，按写入时的单价算好，账号服务不再重算</li>
 *   <li>{@code eventCount}：累计了多少次上报，用于发现「上报次数异常但 token 为 0」</li>
 * </ul>
 *
 * <p>注意：{@code cachedInputTokens} 早期数据为 0（上游还没透出缓存字段），
 * 不能据此判断「没有缓存命中」，只能判断「没有采集到」。</p>
 */
@Entity
@Table(name = "user_daily_usage", uniqueConstraints = {
        @UniqueConstraint(name = "uk_udu_user_day_model",
                columnNames = {"user_id", "usage_day", "model"})
})
public class UserDailyUsage extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "usage_day", nullable = false)
    private LocalDate usageDay;

    /** 空串表示上报方未提供模型名，不是 null —— 唯一键不允许 null 参与去重。 */
    @Column(name = "model", nullable = false, length = 64)
    private String model = "";

    @Column(name = "input_tokens", nullable = false)
    private long inputTokens;

    @Column(name = "cached_input_tokens", nullable = false)
    private long cachedInputTokens;

    @Column(name = "output_tokens", nullable = false)
    private long outputTokens;

    @Column(name = "total_tokens", nullable = false)
    private long totalTokens;

    @Column(name = "cost_micros", nullable = false)
    private long costMicros;

    @Column(name = "event_count", nullable = false)
    private long eventCount;

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public LocalDate getUsageDay() { return usageDay; }
    public void setUsageDay(LocalDate usageDay) { this.usageDay = usageDay; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model == null ? "" : model; }
    public long getInputTokens() { return inputTokens; }
    public void setInputTokens(long inputTokens) { this.inputTokens = inputTokens; }
    public long getCachedInputTokens() { return cachedInputTokens; }
    public void setCachedInputTokens(long cachedInputTokens) { this.cachedInputTokens = cachedInputTokens; }
    public long getOutputTokens() { return outputTokens; }
    public void setOutputTokens(long outputTokens) { this.outputTokens = outputTokens; }
    public long getTotalTokens() { return totalTokens; }
    public void setTotalTokens(long totalTokens) { this.totalTokens = totalTokens; }
    public long getCostMicros() { return costMicros; }
    public void setCostMicros(long costMicros) { this.costMicros = costMicros; }
    public long getEventCount() { return eventCount; }
    public void setEventCount(long eventCount) { this.eventCount = eventCount; }
}
