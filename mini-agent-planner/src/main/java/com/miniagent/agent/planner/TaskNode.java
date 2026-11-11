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
        Map<String, String> outputBindings,
        Long procedureId,
        Integer procedureVersion,
        Long evolutionProposalId,
        String evolutionCandidateHash,
        String procedureVariant,
        String procedureSnapshotJson,
        int canaryTargetPercent,
        com.miniagent.agent.tool.ToolStatus toolStatus
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
        evolutionCandidateHash = evolutionCandidateHash == null ? "" : evolutionCandidateHash.trim();
        procedureSnapshotJson = procedureSnapshotJson == null ? "" : procedureSnapshotJson.trim();
        procedureVariant = procedureVariant == null || procedureVariant.isBlank()
                ? (evolutionProposalId == null ? "BASELINE" : "CANDIDATE")
                : procedureVariant.trim().toUpperCase(java.util.Locale.ROOT);
        if (!"BASELINE".equals(procedureVariant) && !"CANDIDATE".equals(procedureVariant)) {
            throw new IllegalArgumentException("procedureVariant must be BASELINE or CANDIDATE");
        }
        if (evolutionProposalId == null && !evolutionCandidateHash.isEmpty()) {
            throw new IllegalArgumentException("candidate hash requires an evolution proposal id");
        }
        if (!evolutionCandidateHash.isEmpty() && !evolutionCandidateHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("evolutionCandidateHash must be a lowercase SHA-256 hex digest");
        }
        if (evolutionProposalId != null && (evolutionProposalId <= 0 || evolutionCandidateHash.isBlank())) {
            throw new IllegalArgumentException("Canary assignment requires proposal id and candidate hash");
        }
        if (evolutionProposalId == null && canaryTargetPercent != 0) {
            throw new IllegalArgumentException("Canary target requires proposal identity");
        }
        if ("CANDIDATE".equals(procedureVariant) && evolutionProposalId == null) {
            throw new IllegalArgumentException("CANDIDATE variant requires evolution proposal identity");
        }
    }

    /** 兼容扩展结构化结果字段前的持久化格式和旧调用。 */
    public TaskNode(String id, String name, String capability, List<String> dependsOn,
                    List<String> inputs, List<String> outputs, TaskNodeStatus status, int priority,
                    DoneWhen doneWhen, String toolHint, java.util.Map<String, Object> toolArguments,
                    String compensation, List<String> covers, String lastError, int retryCount,
                    String output, Map<String, String> outputBindings, Long procedureId,
                    Integer procedureVersion, Long evolutionProposalId, String evolutionCandidateHash,
                    String procedureVariant, String procedureSnapshotJson, int canaryTargetPercent) {
        this(id, name, capability, dependsOn, inputs, outputs, status, priority, doneWhen,
                toolHint, toolArguments, compensation, covers, lastError, retryCount, output,
                outputBindings, procedureId, procedureVersion, evolutionProposalId,
                evolutionCandidateHash, procedureVariant, procedureSnapshotJson,
                canaryTargetPercent, null);
    }

    /** 兼容旧快照和既有调用：没有 Procedure 引用时保持空值。 */
    public TaskNode(String id, String name, String capability, List<String> dependsOn,
                    List<String> inputs, List<String> outputs, TaskNodeStatus status, int priority,
                    DoneWhen doneWhen, String toolHint, java.util.Map<String, Object> toolArguments,
                    String compensation, List<String> covers, String lastError, int retryCount,
                    String output, Map<String, String> outputBindings) {
        this(id, name, capability, dependsOn, inputs, outputs, status, priority, doneWhen,
                toolHint, toolArguments, compensation, covers, lastError, retryCount,
                output, outputBindings, null, null, null, "", "BASELINE", "", 0);
    }

    /** 兼容旧持久化格式和既有 Compiler 调用。 */
    public TaskNode(String id, String name, String capability, List<String> dependsOn,
                    List<String> inputs, List<String> outputs, TaskNodeStatus status, int priority,
                    DoneWhen doneWhen, String toolHint, String lastError, int retryCount, String output) {
        this(id, name, capability, dependsOn, inputs, outputs, status, priority, doneWhen, toolHint,
                java.util.Map.of(), "", List.of(), lastError, retryCount, output, Map.of(), null, null, null, "", "BASELINE", "", 0);
    }

    /** 构造器兼容旧快照：没有命名产物时按空绑定处理。 */
    public TaskNode(String id, String name, String capability, List<String> dependsOn,
                    List<String> inputs, List<String> outputs, TaskNodeStatus status, int priority,
                    DoneWhen doneWhen, String toolHint, java.util.Map<String, Object> toolArguments,
                    String compensation, List<String> covers, String lastError, int retryCount,
                    String output) {
        this(id, name, capability, dependsOn, inputs, outputs, status, priority, doneWhen, toolHint,
                toolArguments, compensation, covers, lastError, retryCount, output, Map.of(), null, null, null, "", "BASELINE", "", 0);
    }

    public TaskNode withProcedure(Long procedureId, Integer version) {
        return new TaskNode(this.id, name, capability, dependsOn, inputs, outputs, status, priority,
                doneWhen, toolHint, toolArguments, compensation, covers, lastError, retryCount,
                output, outputBindings, procedureId, version, evolutionProposalId, evolutionCandidateHash, procedureVariant, procedureSnapshotJson, canaryTargetPercent, toolStatus);
    }

    public TaskNode withEvolutionCandidate(Long proposalId, String candidateHash) {
        return withEvolutionAssignment(proposalId, candidateHash, "CANDIDATE", canaryTargetPercent);
    }

    public TaskNode withEvolutionAssignment(Long proposalId, String candidateHash, String variant, int targetPercent) {
        return new TaskNode(id, name, capability, dependsOn, inputs, outputs, status, priority,
                doneWhen, toolHint, toolArguments, compensation, covers, lastError, retryCount,
                output, outputBindings, procedureId, procedureVersion, proposalId, candidateHash, variant,
                procedureSnapshotJson, targetPercent, toolStatus);
    }

    public TaskNode withProcedureSnapshot(String snapshotJson) {
        return new TaskNode(id, name, capability, dependsOn, inputs, outputs, status, priority,
                doneWhen, toolHint, toolArguments, compensation, covers, lastError, retryCount,
                output, outputBindings, procedureId, procedureVersion, evolutionProposalId,
                evolutionCandidateHash, procedureVariant, snapshotJson, canaryTargetPercent, toolStatus);
    }

    public TaskNode withCanaryTargetPercent(int percent) {
        return new TaskNode(id, name, capability, dependsOn, inputs, outputs, status, priority,
                doneWhen, toolHint, toolArguments, compensation, covers, lastError, retryCount,
                output, outputBindings, procedureId, procedureVersion, evolutionProposalId,
                evolutionCandidateHash, procedureVariant, procedureSnapshotJson, percent, toolStatus);
    }

    public TaskNode withProcedureVariant(String variant) {
        return new TaskNode(id, name, capability, dependsOn, inputs, outputs, status, priority,
                doneWhen, toolHint, toolArguments, compensation, covers, lastError, retryCount,
                output, outputBindings, procedureId, procedureVersion, evolutionProposalId,
                evolutionCandidateHash, variant, procedureSnapshotJson, canaryTargetPercent, toolStatus);
    }

    public TaskNode withStatus(TaskNodeStatus s) {
        return new TaskNode(id, name, capability, dependsOn, inputs, outputs, s,
                priority, doneWhen, toolHint, toolArguments, compensation, covers, lastError, retryCount,
                output, outputBindings, procedureId, procedureVersion,
                evolutionProposalId, evolutionCandidateHash, procedureVariant, procedureSnapshotJson, canaryTargetPercent, toolStatus);
    }

    /** 重规划只改节点标识和边，避免遗漏参数、验收和 Procedure 元数据。 */
    TaskNode withIdentityAndDependencies(String nextId, List<String> dependencies) {
        return new TaskNode(nextId, name, capability, dependencies, inputs, outputs, status,
                priority, doneWhen, toolHint, toolArguments, compensation, covers, lastError,
                retryCount, output, outputBindings, procedureId, procedureVersion,
                evolutionProposalId, evolutionCandidateHash, procedureVariant,
                procedureSnapshotJson, canaryTargetPercent, toolStatus);
    }

    public TaskNode withError(String err) {
        return new TaskNode(id, name, capability, dependsOn, inputs, outputs, status,
                priority, doneWhen, toolHint, toolArguments, compensation, covers,
                err == null ? "" : err, retryCount, output, outputBindings, procedureId, procedureVersion,
                evolutionProposalId, evolutionCandidateHash, procedureVariant, procedureSnapshotJson, canaryTargetPercent, toolStatus);
    }

    public TaskNode withRetryInc() {
        return new TaskNode(id, name, capability, dependsOn, inputs, outputs, status,
                priority, doneWhen, toolHint, toolArguments, compensation, covers, lastError, retryCount + 1,
                output, outputBindings, procedureId, procedureVersion,
                evolutionProposalId, evolutionCandidateHash, procedureVariant, procedureSnapshotJson, canaryTargetPercent, toolStatus);
    }

    public TaskNode withToolHint(String hint) {
        return new TaskNode(id, name, capability, dependsOn, inputs, outputs, status,
                priority, doneWhen, hint == null ? "" : hint, toolArguments, compensation, covers,
                lastError, retryCount, output, outputBindings, procedureId, procedureVersion,
                evolutionProposalId, evolutionCandidateHash, procedureVariant, procedureSnapshotJson, canaryTargetPercent, toolStatus);
    }

    public TaskNode withToolArguments(Map<String, Object> args) {
        return new TaskNode(id, name, capability, dependsOn, inputs, outputs, status,
                priority, doneWhen, toolHint, args, compensation, covers,
                lastError, retryCount, output, outputBindings, procedureId, procedureVersion,
                evolutionProposalId, evolutionCandidateHash, procedureVariant, procedureSnapshotJson, canaryTargetPercent, toolStatus);
    }

    public TaskNode withOutput(String out) {
        return new TaskNode(id, name, capability, dependsOn, inputs, outputs, status,
                priority, doneWhen, toolHint, toolArguments, compensation, covers, lastError, retryCount,
                out == null ? "" : out, outputBindings, procedureId, procedureVersion,
                evolutionProposalId, evolutionCandidateHash, procedureVariant, procedureSnapshotJson, canaryTargetPercent, toolStatus);
    }

    public TaskNode withOutput(String legacyOutput, Map<String, String> bindings) {
        return new TaskNode(id, name, capability, dependsOn, inputs, outputs, status,
                priority, doneWhen, toolHint, toolArguments, compensation, covers, lastError, retryCount,
                legacyOutput == null ? "" : legacyOutput, bindings, procedureId, procedureVersion,
                evolutionProposalId, evolutionCandidateHash, procedureVariant, procedureSnapshotJson, canaryTargetPercent, toolStatus);
    }

    /** 工具状态是执行器记录的协议字段，不能从业务正文中的 error/status 猜测。 */
    public TaskNode withToolStatus(com.miniagent.agent.tool.ToolStatus value) {
        return new TaskNode(id, name, capability, dependsOn, inputs, outputs, status,
                priority, doneWhen, toolHint, toolArguments, compensation, covers, lastError,
                retryCount, output, outputBindings, procedureId, procedureVersion,
                evolutionProposalId, evolutionCandidateHash, procedureVariant,
                procedureSnapshotJson, canaryTargetPercent, value);
    }

    TaskNode withPorts(List<String> nextInputs, List<String> nextOutputs) {
        return new TaskNode(id, name, capability, dependsOn, nextInputs, nextOutputs, status,
                priority, doneWhen, toolHint, toolArguments, compensation, covers, lastError,
                retryCount, output, outputBindings, procedureId, procedureVersion,
                evolutionProposalId, evolutionCandidateHash, procedureVariant, procedureSnapshotJson, canaryTargetPercent, toolStatus);
    }

    public TaskNode withCapability(String cap) {
        return new TaskNode(id, name, cap, dependsOn, inputs, outputs, status,
                priority, doneWhen, toolHint, toolArguments, compensation, covers,
                lastError, retryCount, output, outputBindings, procedureId, procedureVersion,
                evolutionProposalId, evolutionCandidateHash, procedureVariant, procedureSnapshotJson, canaryTargetPercent, toolStatus);
    }

    public TaskNode withDoneWhen(DoneWhen next) {
        return new TaskNode(id, name, capability, dependsOn, inputs, outputs, status,
                priority, next == null ? DoneWhen.note() : next, toolHint, toolArguments,
                compensation, covers, lastError, retryCount, output, outputBindings, procedureId, procedureVersion,
                evolutionProposalId, evolutionCandidateHash, procedureVariant, procedureSnapshotJson, canaryTargetPercent, toolStatus);
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
