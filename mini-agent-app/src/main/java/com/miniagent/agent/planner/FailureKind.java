package com.miniagent.agent.planner;

/**
 * 可复现的失败原因（再映射到 {@link FailureClass}）。
 */
public enum FailureKind {
    PARAM_ERROR,
    UNKNOWN_TOOL,
    TOOL_ERROR,
    EVAL_FAILED,
    HARD_GATE,
    DRIFT,
    NO_READY,
    GOAL_BLOCKED,
    ORPHAN_RUNNING,
    GENERIC
}
