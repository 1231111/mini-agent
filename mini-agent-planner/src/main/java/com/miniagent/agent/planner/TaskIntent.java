package com.miniagent.agent.planner;

import java.util.List;
import java.util.Objects;

/**
 * 用户目标的结构化解释。它描述模型理解到的内容，不代替用户原文，也不作为授权来源。
 */
public record TaskIntent(
        String objective,
        List<Deliverable> deliverables,
        List<Input> inputs,
        List<String> constraints,
        List<String> exclusions,
        List<String> acceptanceCriteria,
        boolean needsClarification,
        String clarificationQuestion
) {
    /**
     * Graph completion is owned by the scheduler. Treating this as a node-level
     * criterion creates a cycle: a node cannot prove that every graph node has
     * completed before that node itself has completed.
     */
    public static boolean isSchedulerCompletionCriterion(String criterion) {
        if (criterion == null || criterion.isBlank()) return false;
        String compact = criterion.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[\\s\\p{Punct}，。！？；：、（）【】《》“”‘’]+", "");
        if (containsAny(compact, "不要在", "不应在", "并非所有", "并非全部", "不是所有", "不是全部", "未全部",
                "notall", "allnodesarenot", "allstepsarenot", "alltasksarenot")) return false;
        boolean wholeGraph = containsAny(compact,
                "所有节点", "全部节点", "每个节点", "所有步骤", "全部步骤", "每个步骤",
                "所有阶段", "全部阶段", "每个阶段", "所有任务", "全部任务", "每个任务",
                "所有工作", "全部工作", "整个任务", "整个流程", "整张图", "全图", "allnodes", "everynode",
                "allsteps", "everystep", "allstages", "allphases", "alltasks", "everytask",
                "entiretask", "whole task", "entireworkflow", "wholegraph");
        boolean completion = containsAny(compact, "完成", "结束", "成功", "通过")
                || criterion.toLowerCase(java.util.Locale.ROOT)
                .matches("(?s).*\\b(complete[sd]?|finish(?:ed)?|done|succeed(?:ed)?|pass(?:ed)?)\\b.*");
        return wholeGraph && completion;
    }

    private static boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) {
            if (value.contains(candidate.replaceAll("\\s+", ""))) return true;
        }
        return false;
    }

    public TaskIntent {
        objective = Objects.requireNonNullElse(objective, "");
        deliverables = deliverables == null ? List.of() : List.copyOf(deliverables);
        inputs = inputs == null ? List.of() : List.copyOf(inputs);
        constraints = constraints == null ? List.of() : List.copyOf(constraints);
        exclusions = exclusions == null ? List.of() : List.copyOf(exclusions);
        acceptanceCriteria = acceptanceCriteria == null ? List.of() : List.copyOf(acceptanceCriteria);
        clarificationQuestion = Objects.requireNonNullElse(clarificationQuestion, "");
    }

    public static TaskIntent fromGoal(Goal goal) {
        if (goal == null) {
            return new TaskIntent("", List.of(), List.of(), List.of(), List.of(),
                    List.of(), false, "");
        }
        return new TaskIntent(goal.objective(), List.of(), List.of(), goal.constraints(),
                List.of(), goal.successCriteria(), goal.isClarify(),
                goal.isClarify() ? "请补充完成任务所需的信息。" : "");
    }

    public record Deliverable(String name, String type, String path, boolean required) {
        public Deliverable {
            name = Objects.requireNonNullElse(name, "");
            type = Objects.requireNonNullElse(type, "");
            path = Objects.requireNonNullElse(path, "");
        }
    }

    public record Input(String name, String source, boolean required) {
        public Input {
            name = Objects.requireNonNullElse(name, "");
            source = Objects.requireNonNullElse(source, "");
        }
    }
}
