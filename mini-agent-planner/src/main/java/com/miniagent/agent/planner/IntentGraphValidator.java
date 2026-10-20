package com.miniagent.agent.planner;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 校验任务图是否表达了 TaskIntent 中的硬性语义。
 *
 * <p>只做可确定的映射：交付物必须有真正的生产者节点，输入必须有消费者节点，
 * 排除项不能成为某个节点的动作目标。避免用整图字符串包含来判断 ——
 * 那会把「不得删除原文件」这类说明性文本误判成违规，也会让偶然出现的路径通过验收。</p>
 */
public final class IntentGraphValidator {

    public PlanValidationReport validate(TaskIntent intent, TaskGraph graph) {
        if (intent == null || graph == null) {
            return new PlanValidationReport(List.of(), List.of());
        }
        List<PlanValidationReport.ValidationError> errors = new ArrayList<>();
        List<PlanValidationReport.ValidationWarning> warnings = new ArrayList<>();

        for (TaskIntent.Deliverable deliverable : intent.deliverables()) {
            if (!deliverable.required()) {
                continue;
            }
            String path = normalizePath(deliverable.path());
            String name = normalize(deliverable.name());
            if (path.isEmpty() && name.isEmpty()) {
                continue;
            }
            List<TaskNode> producers = producersOf(graph, path, name);
            if (producers.isEmpty()) {
                String expected = path.isEmpty() ? deliverable.name() : deliverable.path();
                errors.add(new PlanValidationReport.ValidationError(
                        PlanValidationReport.ValidationError.Type.GOAL_GRAPH_MISMATCH,
                        null,
                        "必需交付物没有生产者节点: " + expected,
                        "增加负责生成该交付物的节点，并把路径写进 doneWhen.path 或 outputs。"));
            } else if (producers.size() > 1) {
                warnings.add(new PlanValidationReport.ValidationWarning(
                        PlanValidationReport.ValidationWarning.Type.BEST_PRACTICE,
                        producers.get(0).id(),
                        "同一交付物有多个生产者: " + deliverable.name()
                                + " -> " + producers.stream().map(TaskNode::id).toList()));
            }
        }

        for (TaskIntent.Input input : intent.inputs()) {
            if (!input.required()) {
                continue;
            }
            String name = normalize(input.name());
            String source = normalize(input.source());
            String expected = name.isEmpty() ? source : name;
            if (expected.isEmpty()) {
                continue;
            }
            if (!hasConsumer(graph, name, source)) {
                errors.add(new PlanValidationReport.ValidationError(
                        PlanValidationReport.ValidationError.Type.GOAL_GRAPH_MISMATCH,
                        null,
                        "必需输入没有消费者节点: " + input.name(),
                        "增加读取该输入的节点，并把输入写进节点 inputs 或 toolArguments。"));
            }
        }

        for (String exclusion : intent.exclusions()) {
            String value = normalize(exclusion);
            if (value.length() < 4) {
                continue;
            }
            for (TaskNode node : graph.nodes()) {
                if (normalize(node.name()).contains(value)
                        || normalize(node.capability()).contains(value)) {
                    errors.add(new PlanValidationReport.ValidationError(
                            PlanValidationReport.ValidationError.Type.GOAL_GRAPH_MISMATCH,
                            node.id(),
                            "节点动作直接命中了用户排除项: " + exclusion,
                            "删除该节点，或重新确认用户是否确实要求它。"));
                    break;
                }
            }
        }

        for (String constraint : intent.constraints()) {
            String value = normalize(constraint);
            if (value.length() < 4) {
                continue;
            }
            if (!hasLanding(graph, value)) {
                warnings.add(new PlanValidationReport.ValidationWarning(
                        PlanValidationReport.ValidationWarning.Type.GENERAL,
                        null,
                        "约束未在任务图中找到明确落点: " + constraint));
            }
        }
        return new PlanValidationReport(errors, warnings);
    }

    private static List<TaskNode> producersOf(TaskGraph graph, String path, String name) {
        List<TaskNode> producers = new ArrayList<>();
        for (TaskNode node : graph.nodes()) {
            if (produces(node, path, name)) {
                producers.add(node);
            }
        }
        return producers;
    }

    private static boolean produces(TaskNode node, String path, String name) {
        DoneWhen done = node.doneWhen();
        if (done != null && (done.isFile() || done.isMedia())) {
            String expected = normalizePath(done.path());
            if (!path.isEmpty() && !expected.isEmpty() && expected.equals(path)) {
                return true;
            }
            if (path.isEmpty() && !name.isEmpty() && expected.contains(name)) {
                return true;
            }
        }
        for (String output : node.outputs()) {
            String normalized = normalizePath(output);
            if (!path.isEmpty() && normalized.equals(path)) {
                return true;
            }
            if (path.isEmpty() && !name.isEmpty() && normalized.contains(name)) {
                return true;
            }
        }
        for (String cover : node.covers()) {
            String normalized = normalizePath(cover);
            if (!path.isEmpty() && normalized.equals(path)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasConsumer(TaskGraph graph, String name, String source) {
        for (TaskNode node : graph.nodes()) {
            for (String input : node.inputs()) {
                String normalized = normalize(input);
                if (matches(normalized, name) || matches(normalized, source)) {
                    return true;
                }
            }
            for (Object value : node.toolArguments().values()) {
                String normalized = normalize(String.valueOf(value));
                if (matches(normalized, name)) {
                    return true;
                }
            }
            if (matches(normalize(node.name()), name)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasLanding(TaskGraph graph, String value) {
        for (TaskNode node : graph.nodes()) {
            if (matches(normalize(node.name()), value)
                    || matches(normalize(String.valueOf(node.doneWhen())), value)
                    || matches(normalize(node.toolArguments().toString()), value)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matches(String text, String expected) {
        return !expected.isEmpty() && text.contains(expected);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizePath(String value) {
        return normalize(value).replace('\\', '/');
    }
}
