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
        int recoveryCount,
        PlanRevision planRevision,
        TaskIntent intent,
        GoalCompiler.CompilationSource compilationSource,
        boolean degraded
) {
    public StateSnapshot(long version, String sessionId, String executionId, Goal goal, TaskGraph graph,
                         Map<String, Object> execution, Map<String, Object> environment,
                         List<String> knowledgeRefs, int recoveryCount) {
        this(version, sessionId, executionId, goal, graph, execution, environment, knowledgeRefs,
                recoveryCount, PlanRevision.initial("legacy_state"), null,
                GoalCompiler.CompilationSource.RESUMED, false);
    }

    /** 兼容本次改造前的快照：没有 intent 字段时按 goal 保守还原。 */
    public StateSnapshot(long version, String sessionId, String executionId, Goal goal, TaskGraph graph,
                         Map<String, Object> execution, Map<String, Object> environment,
                         List<String> knowledgeRefs, int recoveryCount, PlanRevision planRevision) {
        this(version, sessionId, executionId, goal, graph, execution, environment, knowledgeRefs,
                recoveryCount, planRevision, null,
                GoalCompiler.CompilationSource.RESUMED, false);
    }

    public StateSnapshot(long version, String sessionId, String executionId, Goal goal, TaskGraph graph,
                         Map<String, Object> execution, Map<String, Object> environment,
                         List<String> knowledgeRefs, int recoveryCount, PlanRevision planRevision,
                         TaskIntent intent) {
        this(version, sessionId, executionId, goal, graph, execution, environment, knowledgeRefs,
                recoveryCount, planRevision, intent,
                GoalCompiler.CompilationSource.RESUMED, false);
    }

    public StateSnapshot {
        execution = execution == null ? Map.of() : Map.copyOf(execution);
        environment = environment == null ? Map.of() : Map.copyOf(environment);
        knowledgeRefs = knowledgeRefs == null ? List.of() : List.copyOf(knowledgeRefs);
        graph = graph == null ? new TaskGraph(List.of()) : graph;
        planRevision = planRevision == null ? PlanRevision.initial("legacy_state") : planRevision;
        intent = intent == null ? TaskIntent.fromGoal(goal) : intent;
        compilationSource = compilationSource == null
                ? GoalCompiler.CompilationSource.RESUMED : compilationSource;
    }

    public long planVersion() { return planRevision.planVersion(); }

    @JsonIgnore
    public StateSnapshot withGraph(TaskGraph g) {
        return new StateSnapshot(version, sessionId, executionId, goal, g,
                execution, environment, knowledgeRefs, recoveryCount, planRevision, intent,
                compilationSource, degraded);
    }

    @JsonIgnore
    public StateSnapshot withGoal(Goal g) {
        return new StateSnapshot(version, sessionId, executionId, g, graph,
                execution, environment, knowledgeRefs, recoveryCount, planRevision, intent,
                compilationSource, degraded);
    }

    @JsonIgnore
    public StateSnapshot withRecoveryInc() {
        return new StateSnapshot(version, sessionId, executionId, goal, graph,
                execution, environment, knowledgeRefs, recoveryCount + 1, planRevision, intent,
                compilationSource, degraded);
    }

    @JsonIgnore
    public StateSnapshot withExecution(Map<String, Object> exec) {
        return new StateSnapshot(version, sessionId, executionId, goal, graph,
                exec, environment, knowledgeRefs, recoveryCount, planRevision, intent,
                compilationSource, degraded);
    }

    @JsonIgnore
    public StateSnapshot withIntent(TaskIntent next) {
        return new StateSnapshot(version, sessionId, executionId, goal, graph,
                execution, environment, knowledgeRefs, recoveryCount, planRevision,
                next == null ? intent : next, compilationSource, degraded);
    }

    @JsonIgnore
    public StateSnapshot revisePlan(TaskGraph nextGraph, Goal nextGoal, String reason) {
        return revisePlan(nextGraph, nextGoal, null, compilationSource, degraded, reason);
    }

    @JsonIgnore
    public StateSnapshot revisePlan(TaskGraph nextGraph, Goal nextGoal, TaskIntent nextIntent,
                                    String reason) {
        return revisePlan(nextGraph, nextGoal, nextIntent, compilationSource, degraded, reason);
    }

    @JsonIgnore
    public StateSnapshot revisePlan(TaskGraph nextGraph, Goal nextGoal, TaskIntent nextIntent,
                                    GoalCompiler.CompilationSource nextSource, boolean nextDegraded,
                                    String reason) {
        return new StateSnapshot(version, sessionId, executionId,
                nextGoal == null ? goal : nextGoal, nextGraph == null ? graph : nextGraph,
                execution, environment, knowledgeRefs, recoveryCount, planRevision.next(reason),
                nextIntent == null ? intent : nextIntent,
                nextSource == null ? compilationSource : nextSource, nextDegraded);
    }

    @JsonIgnore
    public StateSnapshot bumpVersion() {
        return new StateSnapshot(version + 1, sessionId, executionId, goal, graph,
                execution, environment, knowledgeRefs, recoveryCount, planRevision, intent,
                compilationSource, degraded);
    }
}
