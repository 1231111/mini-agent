package com.miniagent.agent.execution;

import java.util.EnumSet;
import java.util.Set;

/** 可恢复执行状态机。UNKNOWN 禁止自动重试。 */
public enum ActionExecutionStatus {
    PLANNED, READY, RUNNING, SUCCEEDED, FAILED, TIMEOUT, CANCELLED, UNKNOWN;

    public boolean terminal() {
        return this == SUCCEEDED || this == FAILED || this == TIMEOUT
                || this == CANCELLED || this == UNKNOWN;
    }

    public boolean canTransitionTo(ActionExecutionStatus next) {
        if (next == null || next == this) return false;
        Set<ActionExecutionStatus> allowed = switch (this) {
            case PLANNED -> EnumSet.of(READY, CANCELLED);
            case READY -> EnumSet.of(RUNNING, CANCELLED);
            case RUNNING -> EnumSet.of(SUCCEEDED, FAILED, TIMEOUT, CANCELLED, UNKNOWN);
            case FAILED, TIMEOUT -> EnumSet.of(READY);
            case SUCCEEDED, CANCELLED, UNKNOWN -> EnumSet.noneOf(ActionExecutionStatus.class);
        };
        return allowed.contains(next);
    }
}
