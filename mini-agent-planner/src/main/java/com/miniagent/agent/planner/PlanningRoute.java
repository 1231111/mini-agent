package com.miniagent.agent.planner;

/** A per-turn routing decision; it grants no tool permissions and is never cached by session. */
public record PlanningRoute(boolean useDag, String source, String reason, Boolean artifactDeliveryRequested) {
    public PlanningRoute(boolean useDag, String source, String reason) {
        this(useDag, source, reason, null);
    }
    public static PlanningRoute direct(String source, String reason) {
        return new PlanningRoute(false, source, reason);
    }

    public static PlanningRoute dag(String source, String reason) {
        return new PlanningRoute(true, source, reason);
    }
}
