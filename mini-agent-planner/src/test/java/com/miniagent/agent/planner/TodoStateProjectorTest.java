package com.miniagent.agent.planner;

import com.miniagent.agent.todo.TaskTodoStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.*;

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
    void confirmByTodoIdMovesOnlyTheExplicitNode() {
        TodoStateProjector projector = new TodoStateProjector(null);
        TaskGraph graph = new TaskGraph(List.of(
                node("n1", "问用户 A", TaskNodeStatus.AWAITING_CONFIRM),
                node("n2", "问用户 B", TaskNodeStatus.AWAITING_CONFIRM)));
        TaskGraph next = projector.confirmByTodoId(graph, 2);
        assertEquals(
                TaskNodeStatus.AWAITING_CONFIRM,
                next.byId("n1").status());
        assertEquals(TaskNodeStatus.PENDING, next.byId("n2").status());
        assertSame(graph, projector.confirmByTodoId(graph, 99));
    }

    @Test
    void unknownOutcomeRemainsVisibleAndIsNotAnApprovalRequest() {
        TaskTodoStore store = new TaskTodoStore(tmp.toString());
        TodoStateProjector projector = new TodoStateProjector(store);
        TaskNode unknown = node("n1", "提交订单", TaskNodeStatus.OUTCOME_UNKNOWN)
                .withToolStatus(com.miniagent.agent.tool.ToolStatus.UNKNOWN)
                .withError("实际副作用尚未核验");
        TaskGraph graph = new TaskGraph(List.of(unknown));

        projector.project("s", graph);

        assertEquals(1, store.get("s").size());
        assertEquals(TaskTodoStore.Status.blocked, store.get("s").get(0).status());
        assertSame(graph, projector.confirmByTodoId(graph, 1));
    }

    @Test
    void semanticChecksRemainFrozenAcrossTodoUpdatesPersistenceAndGraphCompletion() throws Exception {
        TaskTodoStore store = new TaskTodoStore(tmp.toString());
        TodoStateProjector projector = new TodoStateProjector(store);
        DoneWhen contract = DoneWhen.note().withChecks(List.of("排除退款订单", "逐月统计"));
        TaskNode running = new TaskNode("n1", "统计销售", "code", null, null, null,
                TaskNodeStatus.RUNNING, 0, contract, "", "", 0, "");
        projector.project("s", new TaskGraph(List.of(running)));
        String serialized = store.get("s").getFirst().doneWhen();
        assertEquals(contract, PlannerStateJson.MAPPER.readValue(serialized, DoneWhen.class));
        String[] error = new String[1];
        assertNull(store.update("s", 1, "completed", "done", "统计结果", error));
        assertTrue(error[0].contains("DAG"));
        assertEquals(TaskTodoStore.Status.in_progress, store.get("s").getFirst().status());
        store.set("s", List.of(Map.of("id", 7, "content", "改名绕过", "status", "completed",
                "done_when", "note_required", "evidence", "done")));
        assertEquals(1, store.get("s").getFirst().id());
        assertEquals(serialized, store.get("s").getFirst().doneWhen());
        TaskTodoStore restored = new TaskTodoStore(tmp.toString());
        assertEquals(serialized, restored.get("s").getFirst().doneWhen());
        assertEquals(TaskTodoStore.Status.in_progress, restored.get("s").getFirst().status());
        projector.project("s", new TaskGraph(List.of(running.withStatus(TaskNodeStatus.SUCCESS).withOutput("已验收统计结果"))));
        assertEquals(TaskTodoStore.Status.completed, store.get("s").getFirst().status());
    }

    @Test
    void structuredTodoCannotPublishUncheckedCompletedDuringSet() {
        TaskTodoStore store = new TaskTodoStore(tmp.toString());
        store.set("s", List.of(Map.of("id", 1, "content", "统计", "status", "completed",
                "done_when", Map.of("type", "note_required", "checks", List.of("排除退款订单")),
                "evidence", "自称完成")));
        assertEquals(TaskTodoStore.Status.in_progress, store.get("s").getFirst().status());
        assertTrue(store.get("s").getFirst().doneWhen().contains("排除退款订单"));
    }

    @Test
    void modelClearAndArchivedReloadCannotEraseFrozenDagChecksButUserClearIsAllowed() {
        TaskTodoStore store = new TaskTodoStore(tmp.toString());
        DoneWhen contract = DoneWhen.note().withChecks(List.of("排除退款订单"));
        TaskNode running = new TaskNode("n1", "统计销售", "code", null, null, null,
                TaskNodeStatus.RUNNING, 0, contract, "", "", 0, "");
        new TodoStateProjector(store).project("s", new TaskGraph(List.of(running)));
        assertFalse(store.clearFromModel("s"));
        assertEquals(TaskTodoStore.Status.in_progress, store.get("s").getFirst().status());
        // Trusted user/task lifecycle clear remains available and archives the original contract.
        store.clear("s");
        assertTrue(store.get("s").isEmpty());
        TaskTodoStore restored = new TaskTodoStore(tmp.toString());
        restored.set("s", List.of(Map.of("id", 9, "content", "跳过验收", "status", "completed",
                "done_when", "note_required", "evidence", "done")));
        assertTrue(restored.get("s").isEmpty());
        assertFalse(restored.clearFromModel("s"));
        assertTrue(restored.restoreCleared("s").getFirst().doneWhen().contains("排除退款订单"));
    }

    private static TaskNode node(String id, String name, TaskNodeStatus status) {
        return new TaskNode(
                id, name, "web", null, null, null, status, 0,
                DoneWhen.note(), "", "", 0, "");
    }
}
