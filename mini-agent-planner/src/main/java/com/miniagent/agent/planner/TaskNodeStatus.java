package com.miniagent.agent.planner;

/**
 * 任务图节点状态机。
 * PENDING → READY → RUNNING → SUCCESS | FAILED → RECOVERING → PENDING/READY
 * 关键步可进入 AWAITING_CONFIRM（与 Todo confirm 对齐）。
 */
public enum TaskNodeStatus {
    PENDING,
    READY,
    RUNNING,
    SUCCESS,
    FAILED,
    RECOVERING,
    AWAITING_CONFIRM,
    CANCELLED,
    /** 执行可能已产生副作用，核验前不得自动恢复或重放。 */
    OUTCOME_UNKNOWN
}
