package com.miniagent.agent.core;

/**
 * Agent 循环的实时流式输出回调。
 *
 * 设计目的：把模型的「思考 + 工具规划 + 答案」实时推给前端，
 * 让用户看到 Agent 正在干活，而不是盯着空白屏幕等待。
 *
 * 所有方法都应是非阻塞、幂等安全的（实现里通常就是往 SseEmitter 发一个事件）。
 * 任意方法抛异常都不应中断 Agent 循环，实现方需自行吞掉发送异常。
 */
public interface AgentStreamSink {

    /** 模型思考增量（reasoning / thinking 内容），逐段到达。 */
    void onThinking(String delta);

    /** 最终答案的 token 增量，逐字/逐段到达。 */
    void onAnswerToken(String delta);

    /**
     * 进入新一轮工具调用前触发：提示前端清空当前已流出的答案增量，
     * 因为本轮流出的文本只是中间思考，真正答案在后续轮次。
     */
    default void onAnswerReset() {}

    /**
     * 当前子目标发生变化时触发（框架维护的 sub-goal 栈推进）。
     * @param text  当前活动子目标的可读描述
     * @param done  已完成的子目标数
     * @param total 子目标总数
     */
    default void onSubGoal(String text, int done, int total) {}
}
