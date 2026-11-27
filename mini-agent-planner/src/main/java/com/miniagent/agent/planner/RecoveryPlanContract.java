package com.miniagent.agent.planner;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A recovery may change the route to a result, never the result's frozen
 * acceptance contract. Keep a verifier carrying every unfinished obligation
 * and its causal predecessors, even when the model introduces additional
 * collection or repair nodes.
 *
 * <p>The caller invokes this after {@link PlanningLoop#mergeKeepSuccess};
 * successful old nodes may therefore be present under their original ids, or
 * be supplied by that merge when a direct contract test only contains the new
 * unfinished subgraph. Unfinished obligations must be represented exactly once
 * and their original predecessors must remain in their ancestor chain. A
 * cancelled prerequisite is still an obligation when an unfinished consumer
 * depends on it; unrelated cancelled branches may be omitted.</p>
 */
final class RecoveryPlanContract {
    private RecoveryPlanContract() { }

    static PlanValidationReport validate(StateSnapshot before, TaskGraph candidate) {
        List<PlanValidationReport.ValidationError> errors = new ArrayList<>();
        if (before == null || candidate == null || candidate.isEmpty()) {
            errors.add(error("", "恢复计划为空，不能移除未完成的验收义务"));
            return new PlanValidationReport(errors, List.of());
        }
        Map<String, TaskNode> candidateById = indexCandidate(candidate, errors);
        Map<String, TaskNode> replacements = new HashMap<>();
        Map<String, String> replacementOwners = new HashMap<>();
        Set<String> successfulIds = new HashSet<>();
        Set<String> requiredIds = requiredObligations(before.graph());

        for (TaskNode original : before.graph().nodes()) {
            if (original.status() == TaskNodeStatus.SUCCESS) {
                successfulIds.add(original.id());
                continue;
            }
            if (!requiredIds.contains(original.id())) continue;

            List<TaskNode> matches = candidate.nodes().stream()
                    .filter(candidateNode -> represents(original, candidateNode))
                    .toList();
            if (matches.size() != 1) {
                String detail = matches.isEmpty()
                        ? "恢复图缺少原未完成节点的唯一替代节点"
                        : "原未完成节点在恢复图中存在多个可能替代节点";
                errors.add(error(original.id(), detail + "：" + original.name()));
                continue;
            }

            TaskNode replacement = matches.get(0);
            if (!replacement.outputs().containsAll(original.outputs())
                    || !preserves(original.doneWhen(), replacement.doneWhen())) {
                errors.add(error(original.id(),
                        "恢复节点未保留原节点的全部命名产出和完整冻结验收条件：" + original.name()));
                continue;
            }
            String previousOwner = replacementOwners.putIfAbsent(replacement.id(), original.id());
            if (previousOwner != null && !Objects.equals(previousOwner, original.id())) {
                errors.add(error(original.id(),
                        "一个候选节点不能同时替代多个原未完成义务：" + replacement.id()));
                continue;
            }
            replacements.put(original.id(), replacement);
        }

        // Continue checking all mappings so a malformed graph returns a useful
        // report rather than hiding later missing obligations behind the first
        // ambiguous match.
        for (TaskNode original : before.graph().nodes()) {
            if (!requiredIds.contains(original.id())) continue;
            TaskNode replacement = replacements.get(original.id());
            if (replacement == null) continue;
            Set<String> ancestors = ancestorsOf(replacement, candidateById);

            for (String dependencyId : original.dependsOn()) {
                TaskNode originalDependency = before.graph().byId(dependencyId);
                if (originalDependency == null) {
                    errors.add(error(original.id(),
                            "原节点引用了不存在的前置义务：" + dependencyId));
                    continue;
                }
                TaskNode dependencyReplacement = replacements.get(originalDependency.id());
                String targetId = originalDependency.status() == TaskNodeStatus.SUCCESS
                        ? originalDependency.id()
                        : dependencyReplacement == null ? null : dependencyReplacement.id();
                if (targetId == null) {
                    errors.add(error(original.id(),
                            "原前置义务没有唯一替代节点：" + originalDependency.name()));
                    continue;
                }
                if (!ancestors.contains(targetId)) {
                    errors.add(error(original.id(),
                            "原消费者的祖先链未包含原前置义务的替代节点："
                                    + originalDependency.name()));
                }
            }
        }

        // Candidate dependencies that cannot be resolved are unsafe even when
        // they are not part of an original edge. The only permitted omission is
        // an old SUCCESS id that mergeKeepSuccess supplies before scheduling.
        for (TaskNode candidateNode : candidate.nodes()) {
            for (String dependencyId : candidateNode.dependsOn()) {
                if (candidateById.containsKey(dependencyId) || successfulIds.contains(dependencyId)) continue;
                errors.add(error(candidateNode.id(),
                        "恢复节点引用了无法解析的前置节点：" + dependencyId));
            }
        }
        return new PlanValidationReport(errors, List.of());
    }

    static boolean represents(TaskNode original, TaskNode replacement) {
        if (original == null || replacement == null) return false;
        return (!original.outputs().isEmpty() && replacement.outputs().containsAll(original.outputs()))
                || (original.id() != null && !original.id().isBlank()
                    && Objects.equals(original.id(), replacement.id()))
                || (!original.name().isBlank() && Objects.equals(original.name(), replacement.name()));
    }

    private static boolean preserves(DoneWhen original, DoneWhen replacement) {
        return original != null && replacement != null
                && Objects.equals(original.type(), replacement.type())
                && Objects.equals(original.path(), replacement.path())
                && Objects.equals(original.criteria(), replacement.criteria())
                && replacement.checks().containsAll(original.checks());
    }

    private static Map<String, TaskNode> indexCandidate(TaskGraph candidate,
                                                        List<PlanValidationReport.ValidationError> errors) {
        Map<String, TaskNode> byId = new HashMap<>();
        for (TaskNode node : candidate.nodes()) {
            if (node == null || node.id() == null || node.id().isBlank()) {
                errors.add(error("", "恢复节点缺少唯一 id"));
                continue;
            }
            if (byId.putIfAbsent(node.id(), node) != null) {
                errors.add(error(node.id(), "恢复图包含重复节点 id"));
            }
        }
        return byId;
    }

    private static Set<String> requiredObligations(TaskGraph original) {
        Set<String> required = new HashSet<>();
        ArrayDeque<TaskNode> pending = new ArrayDeque<>();
        for (TaskNode node : original.nodes()) {
            if (node.status() != TaskNodeStatus.SUCCESS && node.status() != TaskNodeStatus.CANCELLED
                    && required.add(node.id())) pending.add(node);
        }
        while (!pending.isEmpty()) {
            for (String dependencyId : pending.remove().dependsOn()) {
                TaskNode dependency = original.byId(dependencyId);
                if (dependency != null && dependency.status() != TaskNodeStatus.SUCCESS
                        && required.add(dependencyId)) pending.add(dependency);
            }
        }
        return required;
    }

    /** Missing old SUCCESS ids are terminal receipt references until the merge. */
    private static Set<String> ancestorsOf(TaskNode consumer, Map<String, TaskNode> candidateById) {
        Set<String> ancestors = new HashSet<>();
        Set<String> visited = new HashSet<>();
        visited.add(consumer.id());
        ArrayDeque<String> pending = new ArrayDeque<>(consumer.dependsOn());
        while (!pending.isEmpty()) {
            String dependencyId = pending.remove();
            if (!visited.add(dependencyId)) continue;
            ancestors.add(dependencyId);
            TaskNode dependency = candidateById.get(dependencyId);
            if (dependency != null) pending.addAll(dependency.dependsOn());
        }
        return ancestors;
    }

    private static PlanValidationReport.ValidationError error(String node, String message) {
        return new PlanValidationReport.ValidationError(PlanValidationReport.ValidationError.Type.GOAL_GRAPH_MISMATCH,
                node, message, "可以添加取证或修复中间节点，但必须保留每个原未完成节点的完整 doneWhen、outputs 及其前置义务因果链；不能靠删除依赖使失败变成功");
    }
}
