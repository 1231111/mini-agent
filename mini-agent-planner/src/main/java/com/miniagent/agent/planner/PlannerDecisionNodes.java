package com.miniagent.agent.planner;

import com.miniagent.agent.trace.AgentStepNode;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** 规划决策相关 Trace 节点（按 executionId 查询用）。 */
public final class PlannerDecisionNodes {

    public static final Set<String> CODES = EnumSet.of(
            AgentStepNode.GOAL_COMPILED,
            AgentStepNode.GRAPH_UPDATED,
            AgentStepNode.PROPOSAL,
            AgentStepNode.STATE_COMMIT,
            AgentStepNode.RECOVERY_LOCAL,
            AgentStepNode.RECOVERY_REPLACE_TOOL,
            AgentStepNode.RECOVERY_REWRITE_GRAPH,
            AgentStepNode.RECOVERY_REVISE_GOAL
    ).stream().map(Enum::name).collect(Collectors.toUnmodifiableSet());

    private PlannerDecisionNodes() {}

    public static boolean isDecision(String stepType) {
        return stepType != null && CODES.contains(stepType.trim().toUpperCase());
    }

    public static <T> List<T> filter(List<T> steps, java.util.function.Function<T, String> stepTypeOf) {
        if (steps == null || steps.isEmpty()) return List.of();
        return steps.stream()
                .filter(s -> isDecision(stepTypeOf.apply(s)))
                .toList();
    }
}
