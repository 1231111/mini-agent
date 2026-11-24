package com.miniagent.account.web.dto;

import com.miniagent.account.entity.UserLlmCallUsage;

import java.time.LocalDateTime;

/**
 * 单次调用明细。字段与上游控制台的列一一对应：
 * 模型 / 推理强度 / 端点 / 类型 / 计费模式 / TOKEN / 费用 / 首 TOKEN / 耗时 / 输出 TPS / 时间 / USER-AGENT。
 *
 * <p>{@code cachedInputTokens} 为 {@code null} 表示上游没有返回缓存明细（未采集），
 * 与 {@code 0}（确认零命中）语义不同。前端必须分开显示，否则用户会把"不知道"读成"没命中"。</p>
 */
public record LlmCallUsageResponse(
        Long id,
        String sessionId,
        String requestId,
        String model,
        String reasoningEffort,
        String endpoint,
        boolean streaming,
        String billingMode,
        String apiKeyHint,
        long inputTokens,
        Long cachedInputTokens,
        long outputTokens,
        long totalTokens,
        long costMicros,
        long ttftMillis,
        long durationMillis,
        double outputTps,
        String userAgent,
        LocalDateTime createdAt) {

    public static LlmCallUsageResponse of(UserLlmCallUsage entity) {
        return new LlmCallUsageResponse(
                entity.getId(),
                entity.getSessionId(),
                entity.getRequestId(),
                entity.getModel(),
                entity.getReasoningEffort(),
                entity.getEndpoint(),
                entity.isStreaming(),
                entity.getBillingMode(),
                entity.getApiKeyHint(),
                entity.getInputTokens(),
                entity.getCachedInputTokens(),
                entity.getOutputTokens(),
                entity.getTotalTokens(),
                entity.getCostMicros(),
                entity.getTtftMillis(),
                entity.getDurationMillis(),
                entity.outputTps(),
                entity.getUserAgent(),
                entity.getCreatedAt());
    }
}
