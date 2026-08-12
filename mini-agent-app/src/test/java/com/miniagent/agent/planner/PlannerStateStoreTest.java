package com.miniagent.agent.planner;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PlannerStateStoreTest {

    @Test
    void casCommitAndConflict() {
        PlannerStateStore store = new PlannerStateStore();
        Goal goal = new Goal("g1", "obj", "NEW_TASK", Map.of(), List.of(), List.of("ok"));
        TaskGraph graph = new TaskGraph(List.of(
                new TaskNode("n1", "a", "general", List.of(), TaskNodeStatus.PENDING,
                        10, "note_required", "", "", 0)));
        StateSnapshot s1 = store.init("sess-1", "exec-1", goal, graph);
        assertEquals(1L, s1.version());

        StateSnapshot next = s1.withGraph(graph.replace(
                graph.byId("n1").withStatus(TaskNodeStatus.SUCCESS)));
        StateSnapshot s2 = store.commit("sess-1", 1L, next);
        assertEquals(2L, s2.version());
        assertEquals(TaskNodeStatus.SUCCESS, s2.graph().byId("n1").status());

        assertThrows(PlannerStateStore.VersionConflictException.class,
                () -> store.commit("sess-1", 1L, next));
        assertEquals(2L, store.get("sess-1").orElseThrow().version());
    }
}
