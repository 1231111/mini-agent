package com.miniagent.agent.planner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FilePlannerStatePersistenceTest {

    @TempDir
    Path dir;

    @Test
    void casAcrossReload() {
        FilePlannerStatePersistence persist = new FilePlannerStatePersistence(dir.toString());
        PlannerStateStore store = new PlannerStateStore(persist);
        Goal goal = new Goal("g1", "obj", "NEW_TASK", Map.of(), List.of(), List.of("ok"));
        TaskGraph graph = new TaskGraph(List.of(
                new TaskNode("n1", "a", "general", List.of(), TaskNodeStatus.PENDING,
                        10, "note_required", "", "", 0)));
        store.init("s1", "e1", goal, graph);

        PlannerStateStore other = new PlannerStateStore(persist);
        StateSnapshot loaded = other.get("s1").orElseThrow();
        assertEquals(1L, loaded.version());

        StateSnapshot next = loaded.withGraph(graph.replace(
                graph.byId("n1").withStatus(TaskNodeStatus.SUCCESS)));
        StateSnapshot committed = other.commit("s1", 1L, next);
        assertEquals(2L, committed.version());

        assertThrows(PlannerStateStore.VersionConflictException.class,
                () -> store.commit("s1", 1L, next));
        assertEquals(2L, store.get("s1").orElseThrow().version());
    }
}
