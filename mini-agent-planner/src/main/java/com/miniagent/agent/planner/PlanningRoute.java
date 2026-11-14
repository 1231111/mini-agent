package com.miniagent.agent.planner;

/** A per-turn routing decision; it grants no tool permissions and is never cached by session. */
public record PlanningRoute(Strategy strategy, String source, String reason, Boolean artifactDeliveryRequested) {
    public enum Strategy { DIRECT, DAG, UNRESOLVED }

    public PlanningRoute {
        strategy = strategy == null ? Strategy.UNRESOLVED : strategy;
    }

    public PlanningRoute(boolean useDag, String source, String reason, Boolean artifactDeliveryRequested) {
        this(useDag ? Strategy.DAG : Strategy.DIRECT, source, reason, artifactDeliveryRequested);
    }

    public PlanningRoute(boolean useDag, String source, String reason) {
        this(useDag, source, reason, null);
    }
    public static PlanningRoute direct(String source, String reason) {
        return new PlanningRoute(false, source, reason);
    }

    public static PlanningRoute dag(String source, String reason) {
        return new PlanningRoute(true, source, reason);
    }

    public static PlanningRoute unresolved(String reason) {
        return new PlanningRoute(Strategy.UNRESOLVED, "unresolved", reason, null);
    }

    public boolean useDag() { return strategy == Strategy.DAG; }

    public boolean resolved() { return strategy != Strategy.UNRESOLVED; }

    public String routeName() { return strategy.name().toLowerCase(java.util.Locale.ROOT); }
}
