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
