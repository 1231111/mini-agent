package com.miniagent.agent.core;

import com.miniagent.agent.tool.ToolStatus;

/**
 * 工具执行事实的下游出口（记忆事件流）。
 *
 * <p>这是 {@code AgentEvent.EventType.TOOL_EXECUTION} 的<b>唯一生产者</b>。
 * 之所以做成窄接口而不是直接注入 {@code MemoryManager}：主循环不该知道「记忆」这件事，
 * 它只负责上报「某个工具执行完了、终态是什么」，怎么落库、要不要落库由接线方决定。</p>
 *
 * <p>可选注入。为 {@code null} 时静默丢弃 —— 单测里手工构造 {@code AgentLoop} 不受影响。</p>
 */
@FunctionalInterface
public interface ToolExecutionSink {

    /**
     * @param sessionId  会话 id
     * @param turn       主循环第几轮
     * @param toolName   工具名
     * @param status     结构化终态。调用方已排除 {@link ToolStatus#AWAITING_USER}
     *                   （工具压根没执行，上报成一次执行是假事实）
     * @param resultText 结果原文，已由调用方截断
     */
    void onToolExecuted(String sessionId, int turn, String toolName,
                        ToolStatus status, String resultText);
}
