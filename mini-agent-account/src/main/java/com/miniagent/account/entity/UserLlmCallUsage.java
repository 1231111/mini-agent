package com.miniagent.account.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;

/**
 * 单次 LLM 调用明细。
 *
 * <p>与 {@link UserDailyUsage} 的分工：这张表回答「这一次」——首 token 多久、总耗时多久、
 * 推理强度多少、走的哪个端点；聚合表回答「这一天」。两者不是重复存储：
 * 聚合表按天预计算，查询便宜；明细表保存每次调用的延迟分布，能算 P95。</p>
 *
 * <p>不可变：写入后不更新。因此不继承 {@code BaseEntity}，没有 {@code updatedAt}。</p>
 *
 * <p>{@code requestId} 上有唯一约束，重复上报只会留一行。调用方必须填一个稳定的值
 * （模型服务返回的响应 id 最合适）；拿不到时用 {@code sessionId + turn + 时间戳} 兜底，
 * 但要接受「同一秒内同轮重试会被判为重复」这个后果。</p>
 */
@Entity
@Table(name = "user_llm_call_usage", uniqueConstraints = {
        @UniqueConstraint(name = "uk_ulcu_request", columnNames = {"request_id"})
})
public class UserLlmCallUsage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "session_id", nullable = false, length = 100)
    private String sessionId = "";

    @Column(name = "request_id", nullable = false, length = 128)
    private String requestId;

    @Column(name = "model", nullable = false, length = 64)
    private String model = "";

    @Column(name = "reasoning_effort", nullable = false, length = 32)
    private String reasoningEffort = "";

    @Column(name = "endpoint", nullable = false, length = 64)
    private String endpoint = "";

    /** true = 流式调用；非流式调用没有首 token 的概念，{@code ttftMillis} 恒为 0。 */
    @Column(name = "streaming", nullable = false)
    private boolean streaming = true;

    @Column(name = "billing_mode", nullable = false, length = 16)
    private String billingMode = "TOKEN";

    /** 密钥末四位，仅用于区分「哪个 key 在跑量」，不存原文。 */
    @Column(name = "api_key_hint", nullable = false, length = 16)
    private String apiKeyHint = "";

    @Column(name = "input_tokens", nullable = false)
    private long inputTokens;

    /**
     * 缓存命中的输入 token。{@code null} = 上游未返回（未采集），0 = 确认零命中。
     *
     * <p>不能默认成 0：那会让"不知道"看起来像"零命中"，下游按全价计费，
     * 而缓存价通常只有十分之一，账单会偏高 2~3 倍且没有任何报错。</p>
     */
    @Column(name = "cached_input_tokens")
    private Long cachedInputTokens;

    @Column(name = "output_tokens", nullable = false)
    private long outputTokens;

    @Column(name = "total_tokens", nullable = false)
    private long totalTokens;

    @Column(name = "cost_micros", nullable = false)
    private long costMicros;

    @Column(name = "ttft_millis", nullable = false)
    private long ttftMillis;

    @Column(name = "duration_millis", nullable = false)
    private long durationMillis;

    @Column(name = "user_agent", nullable = false, length = 256)
    private String userAgent = "";

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    /** 输出速度（token/秒）。用总耗时算，包含首 token 等待——这是调用方真实感受到的速度。 */
    public double outputTps() {
        if (durationMillis <= 0) return 0d;
        return outputTokens * 1000d / durationMillis;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId == null ? "" : sessionId; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model == null ? "" : model; }
    public String getReasoningEffort() { return reasoningEffort; }
    public void setReasoningEffort(String reasoningEffort) {
        this.reasoningEffort = reasoningEffort == null ? "" : reasoningEffort;
    }
    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint == null ? "" : endpoint; }
    public boolean isStreaming() { return streaming; }
    public void setStreaming(boolean streaming) { this.streaming = streaming; }
    public String getBillingMode() { return billingMode; }
    public void setBillingMode(String billingMode) {
        this.billingMode = billingMode == null || billingMode.isBlank() ? "TOKEN" : billingMode;
    }
    public String getApiKeyHint() { return apiKeyHint; }
    public void setApiKeyHint(String apiKeyHint) {
        this.apiKeyHint = apiKeyHint == null ? "" : apiKeyHint;
    }
    public long getInputTokens() { return inputTokens; }
    public void setInputTokens(long inputTokens) { this.inputTokens = inputTokens; }
    public Long getCachedInputTokens() { return cachedInputTokens; }
    public void setCachedInputTokens(Long cachedInputTokens) { this.cachedInputTokens = cachedInputTokens; }
    public long getOutputTokens() { return outputTokens; }
    public void setOutputTokens(long outputTokens) { this.outputTokens = outputTokens; }
    public long getTotalTokens() { return totalTokens; }
    public void setTotalTokens(long totalTokens) { this.totalTokens = totalTokens; }
    public long getCostMicros() { return costMicros; }
    public void setCostMicros(long costMicros) { this.costMicros = costMicros; }
    public long getTtftMillis() { return ttftMillis; }
    public void setTtftMillis(long ttftMillis) { this.ttftMillis = ttftMillis; }
    public long getDurationMillis() { return durationMillis; }
    public void setDurationMillis(long durationMillis) { this.durationMillis = durationMillis; }
    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String userAgent) { this.userAgent = userAgent == null ? "" : userAgent; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
