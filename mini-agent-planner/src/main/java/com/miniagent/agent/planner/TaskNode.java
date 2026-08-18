package com.miniagent.agent.planner;

import java.util.List;

/**
 * DAG 节点。inputs/outputs 是数据流名；output 是 SUCCESS 后的产物正文。
 */
public record TaskNode(
        String id,
        String name,
        String capability,
        List<String> dependsOn,
        List<String> inputs,
        List<String> outputs,
        TaskNodeStatus status,
        int priority,
        DoneWhen doneWhen,
        String toolHint,
        java.util.Map<String, Object> toolArguments,
        String compensation,
        List<String> covers,
        String lastError,
        int retryCount,
        String output
) {
    public TaskNode {
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
        inputs = inputs == null ? List.of() : List.copyOf(inputs);
        outputs = outputs == null ? List.of() : List.copyOf(outputs);
        status = status == null ? TaskNodeStatus.PENDING : status;
        doneWhen = doneWhen == null ? DoneWhen.note() : doneWhen;
        toolHint = toolHint == null ? "" : toolHint;
        toolArguments = toolArguments == null ? java.util.Map.of() : java.util.Map.copyOf(toolArguments);
        compensation = compensation == null ? "" : compensation;
        covers = covers == null ? List.of() : List.copyOf(covers);
        lastError = lastError == null ? "" : lastError;
        capability = capability == null ? "" : capability;
        name = name == null ? "" : name;
        output = output == null ? "" : output;
    }

    /** 兼容旧持久化格式和既有 Compiler 调用。 */
    public TaskNode(String id, String name, String capability, List<String> dependsOn,
                    List<String> inputs, List<String> outputs, TaskNodeStatus status, int priority,
                    DoneWhen doneWhen, String toolHint, String lastError, int retryCount, String output) {
        this(id, name, capability, dependsOn, inputs, outputs, status, priority, doneWhen, toolHint,
                java.util.Map.of(), "", List.of(), lastError, retryCount, output);
    }

    public TaskNode withStatus(TaskNodeStatus s) {
        return new TaskNode(id, name, capability, dependsOn, inputs, outputs, s,
                priority, doneWhen, toolHint, toolArguments, compensation, covers, lastError, retryCount, output);
    }

    public TaskNode withError(String err) {
        return new TaskNode(id, name, capability, dependsOn, inputs, outputs, status,
                priority, doneWhen, toolHint, toolArguments, compensation, covers,
                err == null ? "" : err, retryCount, output);
    }

    public TaskNode withRetryInc() {
        return new TaskNode(id, name, capability, dependsOn, inputs, outputs, status,
                priority, doneWhen, toolHint, toolArguments, compensation, covers, lastError, retryCount + 1, output);
    }

    public TaskNode withToolHint(String hint) {
        return new TaskNode(id, name, capability, dependsOn, inputs, outputs, status,
                priority, doneWhen, hint == null ? "" : hint, toolArguments, compensation, covers,
                lastError, retryCount, output);
    }

    public TaskNode withOutput(String out) {
        return new TaskNode(id, name, capability, dependsOn, inputs, outputs, status,
                priority, doneWhen, toolHint, toolArguments, compensation, covers, lastError, retryCount,
                out == null ? "" : out);
    }
}
