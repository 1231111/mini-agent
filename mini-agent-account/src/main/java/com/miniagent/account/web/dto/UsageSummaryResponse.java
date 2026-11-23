package com.miniagent.account.web.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 用量汇总响应。
 *
 * <p>所有金额都是 {@code costMicros}（百万分之一元）。前端展示时除以 1_000_000，
 * 接口层不做单位换算 —— 换一次就有一次精度损失，而且不同展示位（¥ vs $）需要不同格式。</p>
 *
 * <p>{@code cachedInputTokens} 是 {@code inputTokens} 的子集，不是额外量。
 * 缓存命中率高说明上下文复用得好，不代表总消耗低。</p>
 */
public record UsageSummaryResponse(
        Long userId,
        LocalDate from,
        LocalDate to,
        long inputTokens,
        long cachedInputTokens,
        long outputTokens,
        long totalTokens,
        long costMicros,
        long eventCount,
        long dayCount,
        List<ModelUsage> byModel,
        List<DayUsage> byDay) {

    public record ModelUsage(
            String model,
            long inputTokens,
            long cachedInputTokens,
            long outputTokens,
            long totalTokens,
            long costMicros,
            long eventCount) {
    }

    public record DayUsage(
            LocalDate day,
            long inputTokens,
            long cachedInputTokens,
            long outputTokens,
            long totalTokens,
            long costMicros,
            long eventCount) {
    }
}
