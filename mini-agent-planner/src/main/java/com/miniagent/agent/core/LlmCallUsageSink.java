package com.miniagent.agent.core;

/**
 * 单次 LLM 调用的用量出口。
 *
 * <p>接口定义在 planner、实现放在 app：planner 不能依赖 app，而记账要访问数据库与云端。
 * 与 {@code TokenUsagePersistence} 同一个模式。</p>
 *
 * <p>实现方必须满足两条：<b>不阻塞主流程</b>（失败只记日志，不能让对话失败）、
 * <b>不抛异常</b>（调用点没有 try/catch 包裹）。</p>
 */
public interface LlmCallUsageSink {

    /** 记录一次调用。{@code requestId} 是幂等键，重复上报由下游唯一索引兜住。 */
    void record(CallUsage usage);

    /**
     * 一次调用的快照。
     *
     * @param ttftMillis 首 token 耗时；非流式或未收到 token 时为 0，与「0 毫秒」不可区分，
     *                   所以同时带 {@code streaming} 让下游能判断这个 0 是否有意义
     * @param costMicros 百万分之一元。目前恒为 0 —— agent 侧还没有价格表，
     *                   计价要等模型单价配置接入后再填
     */
    record CallUsage(
            String sessionId,
            int turn,
            String requestId,
            String model,
            String reasoningEffort,
            String endpoint,
            boolean streaming,
            String billingMode,
            String apiKeyHint,
            long inputTokens,
            long cachedInputTokens,
            long outputTokens,
            long costMicros,
            long ttftMillis,
            long durationMillis,
            String userAgent) {
    }
}
