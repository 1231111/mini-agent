package com.miniagent.agent.planner;

public interface NodeTerminalCommitPort {
    void commit(String sessionId, long taskId, String actionId, TaskNode node, Result result);

    /** 带失败细节的终态；只关心成败的实现不用覆盖，默认丢弃细节。 */
    default void commit(String sessionId, long taskId, String actionId, TaskNode node,
                        Result result, Detail detail) {
        commit(sessionId, taskId, actionId, node, result);
    }

    enum Result { SUCCESS, FAILURE }

    /**
     * 记忆侧聚类与回放用的证据。
     *
     * @param errorCode     最后一次工具错误码（{@code ToolErrorCode} 名）；无错误时为 null
     * @param failureReason 判定失败的原因；成功时为 null
     * @param userMessage   本轮用户目标
     */
    record Detail(String errorCode, String failureReason, String userMessage) {
    }
}
