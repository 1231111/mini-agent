package com.miniagent.agent.planner;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReadyTaskSelectorTest {

    @Test
    void selectsReadyByDependencyAndPriority() {
        TaskGraph graph = new TaskGraph(List.of(
                new TaskNode("n1", "first", "general", List.of(), TaskNodeStatus.SUCCESS,
                        1, "note_required", "", "", 0),
                new TaskNode("n2", "second", "file_write", List.of("n1"), TaskNodeStatus.PENDING,
                        5, "note_required", "", "", 0),
                new TaskNode("n3", "blocked", "web", List.of("n2"), TaskNodeStatus.PENDING,
                        9, "note_required", "", "", 0),
                new TaskNode("n4", "parallel", "general", List.of("n1"), TaskNodeStatus.PENDING,
                        8, "note_required", "", "", 0)
        )).normalizeForScheduling();
        ReadyTaskSelector sel = new ReadyTaskSelector();
        List<TaskNode> ready = sel.select(graph);
        assertEquals(2, ready.size());
        assertEquals("n4", ready.get(0).id());
        assertEquals("n2", ready.get(1).id());
        assertFalse(graph.hasCycle());
    }

    @Test
    void runningBlocksOtherReady() {
        TaskGraph graph = new TaskGraph(List.of(
                new TaskNode("n1", "a", "general", List.of(), TaskNodeStatus.RUNNING,
                        1, "note_required", "", "", 0),
                new TaskNode("n2", "b", "general", List.of(), TaskNodeStatus.READY,
                        2, "note_required", "", "", 0)
        ));
        assertTrue(new ReadyTaskSelector().select(graph).isEmpty());
    }

    @Test
    void detectsCycle() {
        TaskGraph cycle = new TaskGraph(List.of(
                new TaskNode("a", "a", "general", List.of("b"), TaskNodeStatus.PENDING,
                        1, "note_required", "", "", 0),
                new TaskNode("b", "b", "general", List.of("a"), TaskNodeStatus.PENDING,
                        1, "note_required", "", "", 0)
        ));
        assertTrue(cycle.hasCycle());
        assertTrue(cycle.normalizeForScheduling().readyNodes().isEmpty());
    }
}
