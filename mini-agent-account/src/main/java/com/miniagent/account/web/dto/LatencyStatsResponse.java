package com.miniagent.account.web.dto;

/**
 * 区间延迟统计。
 *
 * <p>{@code ttftSamples} 是参与平均的样本数：只统计 {@code ttftMillis > 0} 的流式调用。
 * 如果它远小于 {@code callCount}，说明这段时间里绝大多数是非流式调用，
 * 此时 {@code avgTtftMillis} 不能代表用户的实际等待。</p>
 *
 * <p>不提供 P95：JPQL 没有窗口函数。要 P95 需要在明细查询里单独取排序后的偏移行，
 * 目前先用平均值 + 最大值，两者差距过大时再去补。</p>
 */
public record LatencyStatsResponse(
        long callCount,
        long ttftSamples,
        double avgTtftMillis,
        double avgDurationMillis,
        long maxDurationMillis) {
}
