package com.miniagent.agent.planner;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A recovery may change the route to a result, never the result's frozen
 * acceptance contract.  Keep a verifier carrying every unfinished obligation
 * even when the model introduces additional collection or repair nodes.
 */
final class RecoveryPlanContract {
    private RecoveryPlanContract() { }

    static PlanValidationReport validate(StateSnapshot before, TaskGraph candidate) {
        List<PlanValidationReport.ValidationError> errors = new ArrayList<>();
        if (before == null || candidate == null || candidate.isEmpty()) {
            errors.add(error("", "恢复计划为空，不能移除未完成的验收义务"));
            return new PlanValidationReport(errors, List.of());
        }
        for (TaskNode original : before.graph().nodes()) {
            if (original.status() == TaskNodeStatus.SUCCESS || original.status() == TaskNodeStatus.CANCELLED) continue;
            boolean retained = candidate.nodes().stream().anyMatch(node ->
                    represents(original, node) && preserves(original.doneWhen(), node.doneWhen()));
            if (!retained) errors.add(error(original.id(),
                    "恢复图必须保留未完成节点的命名产出和完整冻结验收条件：" + original.name()));
        }
        return new PlanValidationReport(errors, List.of());
    }

    static boolean represents(TaskNode original, TaskNode replacement) {
        return original.outputs().isEmpty()
                ? Objects.equals(original.id(), replacement.id())
                    || Objects.equals(original.name(), replacement.name())
                : replacement.outputs().containsAll(original.outputs());
    }

    private static boolean preserves(DoneWhen original, DoneWhen replacement) {
        return original != null && replacement != null
                && Objects.equals(original.type(), replacement.type())
                && Objects.equals(original.path(), replacement.path())
                && Objects.equals(original.criteria(), replacement.criteria())
                && replacement.checks().containsAll(original.checks());
    }

    private static PlanValidationReport.ValidationError error(String node, String message) {
        return new PlanValidationReport.ValidationError(PlanValidationReport.ValidationError.Type.GOAL_GRAPH_MISMATCH,
                node, message, "可以添加取证或修复前置节点，但必须保留原节点的完整 doneWhen 和所有 outputs；不能靠删除条件使失败变成功");
    }
}
