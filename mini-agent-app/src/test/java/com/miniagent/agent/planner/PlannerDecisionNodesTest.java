package com.miniagent.agent.planner;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PlannerDecisionNodesTest {

    @Test
    void filtersOnlyPlannerNodes() {
        List<Map<String, String>> steps = List.of(
                Map.of("type", "RUN_START"),
                Map.of("type", "GOAL_COMPILED"),
                Map.of("type", "PROPOSAL"),
                Map.of("type", "TOOL_CALL"),
                Map.of("type", "RECOVERY_REPLACE_TOOL"),
                Map.of("type", "ANSWER")
        );
        List<Map<String, String>> filtered =
                PlannerDecisionNodes.filter(steps, m -> m.get("type"));
        assertEquals(3, filtered.size());
        assertTrue(PlannerDecisionNodes.isDecision("state_commit"));
        assertFalse(PlannerDecisionNodes.isDecision("TOOL_CALL"));
    }
}
