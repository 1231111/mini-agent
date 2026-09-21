package com.miniagent.agent.planner;

import com.miniagent.agent.todo.TaskTodoStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class TodoStateProjectorTest {

    @TempDir
    Path tmp;

    @Test
    void projectOverwritesHigherRankTodoStatus() {
        TaskTodoStore store = new TaskTodoStore(tmp.toString());
        store.set("s", List.of(Map.of(
                "id", 1, "content", "写报告", "status", "completed")));
        TodoStateProjector projector = new TodoStateProjector(store);
        TaskGraph graph = new TaskGraph(List.of(node(
                "n1", "写报告", TaskNodeStatus.PENDING)));
        projector.project("s", graph);
        assertEquals(TaskTodoStore.Status.pending, store.get("s").get(0).status());
    }

    @Test
    void confirmByTodoIdMovesAwaitingToPending() {
        TodoStateProjector projector = new TodoStateProjector(null);
        TaskGraph graph = new TaskGraph(List.of(node(
                "n1", "问用户", TaskNodeStatus.AWAITING_CONFIRM)));
        TaskGraph next = projector.confirmByTodoId(graph, 1);
        assertEquals(TaskNodeStatus.PENDING, next.byId("n1").status());
        assertSame(graph, projector.confirmByTodoId(graph, 99));
        assertSame(next, projector.confirmFirst(next));
    }

    @Test
    void confirmFirstStopsAtFirstAwaiting() {
        TodoStateProjector projector = new TodoStateProjector(null);
        TaskGraph graph = new TaskGraph(List.of(
                node("n1", "a", TaskNodeStatus.AWAITING_CONFIRM),
                node("n2", "b", TaskNodeStatus.AWAITING_CONFIRM)));
        TaskGraph next = projector.confirmFirst(graph);
        assertEquals(TaskNodeStatus.PENDING, next.byId("n1").status());
        assertEquals(TaskNodeStatus.AWAITING_CONFIRM, next.byId("n2").status());
    }

    private static TaskNode node(String id, String name, TaskNodeStatus status) {
        return new TaskNode(
                id, name, "web", null, null, null, status, 0,
                DoneWhen.note(), "", "", 0, "");
    }
}
