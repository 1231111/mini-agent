package com.miniagent.agent.hook;

/**
 * Stop Hook 裁决（对齐 Claude Code Stop：放行 / 阻止结束 / 注入消息再开一轮）。
 */
public record StopDecision(Action action, String message, String reason) {

    public enum Action {
        /** 不干预，继续原收尾逻辑 */
        PROCEED,
        /** 禁止继续循环，用 message 作为最终回复（可为空则用原 finalText） */
        PREVENT_CONTINUATION,
        /** 拦截收尾：把 message 注入为 SystemMessage，再开一轮 */
        BLOCK_RETRY
    }

    public static StopDecision proceed() {
        return new StopDecision(Action.PROCEED, null, null);
    }

    public static StopDecision prevent(String reason) {
        return new StopDecision(Action.PREVENT_CONTINUATION, null, reason);
    }

    public static StopDecision preventWithAnswer(String answer, String reason) {
        return new StopDecision(Action.PREVENT_CONTINUATION, answer, reason);
    }

    public static StopDecision blockRetry(String systemMessage, String reason) {
        return new StopDecision(Action.BLOCK_RETRY, systemMessage, reason);
    }

    public boolean isProceed() {
        return action == Action.PROCEED;
    }
}
