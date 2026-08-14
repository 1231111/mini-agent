package com.miniagent.agent.planner;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.List;
import java.util.Map;

/**
 * 版本化状态快照（Planner 单一事实源）。
 */
public record StateSnapshot(
        long version,
        String sessionId,
        String executionId,
        Goal goal,
        TaskGraph graph,
        Map<String, Object> execution,
        Map<String, Object> environment,
        List<String> knowledgeRefs,
        int recoveryCount
) {
    public StateSnapshot {
        execution = execution == null ? Map.of() : Map.copyOf(execution);
        environment = environment == null ? Map.of() : Map.copyOf(environment);
        knowledgeRefs = knowledgeRefs == null ? List.of() : List.copyOf(knowledgeRefs);
        graph = graph == null ? new TaskGraph(List.of()) : graph;
    }

    @JsonIgnore
    public StateSnapshot withGraph(TaskGraph g) {
        return new StateSnapshot(version, sessionId, executionId, goal, g,
                execution, environment, knowledgeRefs, recoveryCount);
    }

    @JsonIgnore
    public StateSnapshot withGoal(Goal g) {
        return new StateSnapshot(version, sessionId, executionId, g, graph,
                execution, environment, knowledgeRefs, recoveryCount);
    }

    @JsonIgnore
    public StateSnapshot withRecoveryInc() {
        return new StateSnapshot(version, sessionId, executionId, goal, graph,
                execution, environment, knowledgeRefs, recoveryCount + 1);
    }

    @JsonIgnore
    public StateSnapshot withExecution(Map<String, Object> exec) {
        return new StateSnapshot(version, sessionId, executionId, goal, graph,
                exec, environment, knowledgeRefs, recoveryCount);
    }

    @JsonIgnore
    public StateSnapshot bumpVersion() {
        return new StateSnapshot(version + 1, sessionId, executionId, goal, graph,
                execution, environment, knowledgeRefs, recoveryCount);
    }
}
