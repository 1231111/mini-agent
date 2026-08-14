package com.miniagent.agent.planner;

public enum DomainEventType {
    GRAPH_COMPILED,
    ACTION_STARTED,
    ACTION_COMPLETED,
    ACTION_FAILED,
    NODE_SUCCESS,
    NODE_FAILED,
    STATE_COMMITTED,
    RECOVERY_APPLIED,
    VERSION_CONFLICT
}
