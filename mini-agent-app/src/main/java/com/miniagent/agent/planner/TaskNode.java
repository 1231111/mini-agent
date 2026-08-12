package com.miniagent.agent.planner;

import java.util.List;

/**
 * DAG 节点。
 */
public record TaskNode(
        String id,
        String name,
        String capability,
        List<String> dependsOn,
        TaskNodeStatus status,
        int priority,
        String doneWhen,
        String toolHint,
        String lastError,
        int retryCount
) {
    public TaskNode {
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
        status = status == null ? TaskNodeStatus.PENDING : status;
        doneWhen = doneWhen == null ? "" : doneWhen;
        toolHint = toolHint == null ? "" : toolHint;
        lastError = lastError == null ? "" : lastError;
        capability = capability == null ? "" : capability;
        name = name == null ? "" : name;
    }

    public TaskNode withStatus(TaskNodeStatus s) {
        return new TaskNode(id, name, capability, dependsOn, s, priority, doneWhen, toolHint, lastError, retryCount);
    }

    public TaskNode withError(String err) {
        return new TaskNode(id, name, capability, dependsOn, status, priority, doneWhen, toolHint,
                err == null ? "" : err, retryCount);
    }

    public TaskNode withRetryInc() {
        return new TaskNode(id, name, capability, dependsOn, status, priority, doneWhen, toolHint,
                lastError, retryCount + 1);
    }

    public TaskNode withToolHint(String hint) {
        return new TaskNode(id, name, capability, dependsOn, status, priority, doneWhen,
                hint == null ? "" : hint, lastError, retryCount);
    }
}
