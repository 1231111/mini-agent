package com.miniagent.agent.planner;

public record FailureDiagnosis(
        FailureClass failureClass,
        FailureKind kind,
        String taskId,
        String tool,
        String reason,
        String suggestedFix
) {
    public FailureDiagnosis {
        kind = kind == null ? FailureKind.GENERIC : kind;
        reason = reason == null ? "" : reason;
        suggestedFix = suggestedFix == null ? "" : suggestedFix;
        tool = tool == null ? "" : tool;
        taskId = taskId == null ? "" : taskId;
    }

    /** 兼容旧 5 参调用 */
    public FailureDiagnosis(FailureClass failureClass, String taskId, String tool,
                            String reason, String suggestedFix) {
        this(failureClass, FailureKind.GENERIC, taskId, tool, reason, suggestedFix);
    }
}
