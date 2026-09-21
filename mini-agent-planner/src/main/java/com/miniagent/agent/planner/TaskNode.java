package com.miniagent.agent.planner;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        String output,
        Map<String, String> outputBindings
) {
    static final String BLOCKED_PREFIX = "block:";
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
        outputBindings = outputBindings == null ? Map.of() : Map.copyOf(outputBindings);
    }

    /** 兼容旧持久化格式和既有 Compiler 调用。 */
    public TaskNode(String id, String name, String capability, List<String> dependsOn,
                    List<String> inputs, List<String> outputs, TaskNodeStatus status, int priority,
                    DoneWhen doneWhen, String toolHint, String lastError, int retryCount, String output) {
        this(id, name, capability, dependsOn, inputs, outputs, status, priority, doneWhen, toolHint,
                java.util.Map.of(), "", List.of(), lastError, retryCount, output);
    }

    /** 构造器兼容旧快照：没有命名产物时按空绑定处理。 */
    public TaskNode(String id, String name, String capability, List<String> dependsOn,
                    List<String> inputs, List<String> outputs, TaskNodeStatus status, int priority,
                    DoneWhen doneWhen, String toolHint, java.util.Map<String, Object> toolArguments,
                    String compensation, List<String> covers, String lastError, int retryCount,
                    String output) {
        this(id, name, capability, dependsOn, inputs, outputs, status, priority, doneWhen, toolHint,
                toolArguments, compensation, covers, lastError, retryCount, output, Map.of());
    }

    public TaskNode withStatus(TaskNodeStatus s) {
        return new TaskNode(id, name, capability, dependsOn, inputs, outputs, s,
                priority, doneWhen, toolHint, toolArguments, compensation, covers, lastError, retryCount,
                output, outputBindings);
    }

    public TaskNode withError(String err) {
        return new TaskNode(id, name, capability, dependsOn, inputs, outputs, status,
                priority, doneWhen, toolHint, toolArguments, compensation, covers,
                err == null ? "" : err, retryCount, output, outputBindings);
    }

    public TaskNode withRetryInc() {
        return new TaskNode(id, name, capability, dependsOn, inputs, outputs, status,
                priority, doneWhen, toolHint, toolArguments, compensation, covers, lastError, retryCount + 1,
                output, outputBindings);
    }

    public TaskNode withToolHint(String hint) {
        return new TaskNode(id, name, capability, dependsOn, inputs, outputs, status,
                priority, doneWhen, hint == null ? "" : hint, toolArguments, compensation, covers,
                lastError, retryCount, output, outputBindings);
    }

    public TaskNode withToolArguments(Map<String, Object> args) {
        return new TaskNode(id, name, capability, dependsOn, inputs, outputs, status,
                priority, doneWhen, toolHint, args, compensation, covers,
                lastError, retryCount, output, outputBindings);
    }

    public TaskNode withOutput(String out) {
        return new TaskNode(id, name, capability, dependsOn, inputs, outputs, status,
                priority, doneWhen, toolHint, toolArguments, compensation, covers, lastError, retryCount,
                out == null ? "" : out, outputBindings);
    }

    public TaskNode withOutput(String legacyOutput, Map<String, String> bindings) {
        return new TaskNode(id, name, capability, dependsOn, inputs, outputs, status,
                priority, doneWhen, toolHint, toolArguments, compensation, covers, lastError, retryCount,
                legacyOutput == null ? "" : legacyOutput, bindings);
    }

    TaskNode withPorts(List<String> nextInputs, List<String> nextOutputs) {
        return new TaskNode(id, name, capability, dependsOn, nextInputs, nextOutputs, status,
                priority, doneWhen, toolHint, toolArguments, compensation, covers, lastError,
                retryCount, output, outputBindings);
    }

    public TaskNode withCapability(String cap) {
        return new TaskNode(id, name, cap, dependsOn, inputs, outputs, status,
                priority, doneWhen, toolHint, toolArguments, compensation, covers,
                lastError, retryCount, output, outputBindings);
    }

    public TaskNode withDoneWhen(DoneWhen next) {
        return new TaskNode(id, name, capability, dependsOn, inputs, outputs, status,
                priority, next == null ? DoneWhen.note() : next, toolHint, toolArguments,
                compensation, covers, lastError, retryCount, output, outputBindings);
    }

    /** REPLACE_TOOL 写入的失败工具，不是 Compiler 绑定。 */
    public List<String> blockedTools() {
        if (!toolHint.startsWith(BLOCKED_PREFIX)) {
            return List.of();
        }
        String raw = toolHint.substring(BLOCKED_PREFIX.length()).trim();
        if (raw.isBlank()) {
            return List.of();
        }
        return List.of(raw.split(",")).stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    public String blockedTool() {
        List<String> all = blockedTools();
        return all.isEmpty() ? "" : all.get(all.size() - 1);
    }

    public TaskNode withBlockedTool(String tool) {
        if (tool == null || tool.isBlank()) {
            return this;
        }
        Set<String> set = new LinkedHashSet<>(blockedTools());
        set.add(tool.trim());
        return withToolHint(BLOCKED_PREFIX + String.join(",", set));
    }
}
