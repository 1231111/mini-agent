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
    CANCELLED
}
