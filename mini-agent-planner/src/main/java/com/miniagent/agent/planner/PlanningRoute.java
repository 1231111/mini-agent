package com.miniagent.agent.planner;

/** A per-turn routing decision; it grants no tool permissions and is never cached by session. */
public record PlanningRoute(Strategy strategy, String source, String reason, Boolean artifactDeliveryRequested,
                            com.miniagent.agent.scope.TaskBoundary taskBoundary,
                            DagAction dagAction) {
    public enum Strategy { DIRECT, DAG, UNRESOLVED }
    /** Semantic disposition of the current DAG request; grants no authority or tool permission. */
    public enum DagAction { PLAN, RESUME_EXISTING }

    public PlanningRoute {
        strategy = strategy == null ? Strategy.UNRESOLVED : strategy;
        taskBoundary = taskBoundary == null ? com.miniagent.agent.scope.TaskBoundary.SAME : taskBoundary;
        dagAction = dagAction == null ? DagAction.PLAN : dagAction;
    }

    public PlanningRoute(Strategy strategy, String source, String reason, Boolean artifactDeliveryRequested,
                         com.miniagent.agent.scope.TaskBoundary taskBoundary) {
        this(strategy, source, reason, artifactDeliveryRequested, taskBoundary, DagAction.PLAN);
    }

    public PlanningRoute(Strategy strategy, String source, String reason, Boolean artifactDeliveryRequested) {
        this(strategy, source, reason, artifactDeliveryRequested,
                com.miniagent.agent.scope.TaskBoundary.SAME, DagAction.PLAN);
    }

    public PlanningRoute(boolean useDag, String source, String reason, Boolean artifactDeliveryRequested) {
        this(useDag ? Strategy.DAG : Strategy.DIRECT, source, reason, artifactDeliveryRequested);
    }

    public PlanningRoute(boolean useDag, String source, String reason) {
        this(useDag, source, reason, null);
    }

    public PlanningRoute(boolean useDag, String source, String reason, Boolean artifactDeliveryRequested,
                         com.miniagent.agent.scope.TaskBoundary taskBoundary) {
        this(useDag ? Strategy.DAG : Strategy.DIRECT, source, reason, artifactDeliveryRequested,
                taskBoundary, DagAction.PLAN);
    }

    public PlanningRoute(boolean useDag, String source, String reason, Boolean artifactDeliveryRequested,
                         com.miniagent.agent.scope.TaskBoundary taskBoundary, DagAction dagAction) {
        this(useDag ? Strategy.DAG : Strategy.DIRECT, source, reason, artifactDeliveryRequested,
                taskBoundary, dagAction);
    }

    public static PlanningRoute direct(String source, String reason) {
        return new PlanningRoute(false, source, reason);
    }

    public static PlanningRoute dag(String source, String reason) {
        return new PlanningRoute(true, source, reason);
    }

    public static PlanningRoute dag(String source, String reason,
                                    com.miniagent.agent.scope.TaskBoundary boundary,
                                    DagAction action) {
        return new PlanningRoute(true, source, reason, null, boundary, action);
    }

    public static PlanningRoute resume(String source, String reason) {
        return dag(source, reason, com.miniagent.agent.scope.TaskBoundary.SAME,
                DagAction.RESUME_EXISTING);
    }

    public static PlanningRoute unresolved(String reason) {
        return new PlanningRoute(Strategy.UNRESOLVED, "unresolved", reason, null,
                com.miniagent.agent.scope.TaskBoundary.SAME, DagAction.PLAN);
    }

    public boolean useDag() { return strategy == Strategy.DAG; }

    public boolean resolved() { return strategy != Strategy.UNRESOLVED; }

    public String routeName() { return strategy.name().toLowerCase(java.util.Locale.ROOT); }
}
