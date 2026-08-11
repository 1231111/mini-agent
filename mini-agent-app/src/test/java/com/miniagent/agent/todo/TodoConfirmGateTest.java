package com.miniagent.agent.todo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TodoConfirmGateTest {

    @TempDir
    Path temp;

    TaskTodoStore store;
    String sid = "confirm-gate";

    @BeforeEach
    void setUp() {
        store = new TaskTodoStore(temp.resolve("todos").toString());
    }

    @Test
    void criticalGoal_entersAwaitingConfirm() {
        store.set(sid, List.of(
                Map.of("id", 1, "content", "准备材料", "done_when", "note_required"),
                Map.of("id", 2, "content", "最终交付报告", "done_when", "note_required")
        ));
        assertEquals(TaskTodoStore.Status.in_progress, store.get(sid).get(0).status());

        assertNotNull(store.update(sid, 1, "completed", null, "材料齐了", new String[1]));
        var items = store.get(sid);
        assertEquals(TaskTodoStore.Status.awaiting_confirm, find(items, 2).status());
        assertTrue(store.hasAwaitingConfirm(sid));

        String[] err = new String[1];
        assertNull(store.update(sid, 2, "in_progress", null, null, err));
        assertTrue(err[0].contains("confirm"));

        assertNotNull(store.confirm(sid, 2, "CONFIRM 上游无偏差", new String[1]));
        assertEquals(TaskTodoStore.Status.in_progress, find(store.get(sid), 2).status());
        assertFalse(store.hasAwaitingConfirm(sid));
    }

    @Test
    void manyDepends_needsConfirm() {
        store.set(sid, List.of(
                Map.of("id", 1, "content", "A", "done_when", "note_required", "depends_on", List.of()),
                Map.of("id", 2, "content", "B", "done_when", "note_required", "depends_on", List.of(1)),
                Map.of("id", 3, "content", "C", "done_when", "note_required", "depends_on", List.of(1, 2)),
                Map.of("id", 4, "content", "D聚合", "done_when", "note_required", "depends_on", List.of(1, 2, 3))
        ));
        assertNotNull(store.update(sid, 1, "completed", null, "a", new String[1]));
        assertNotNull(store.update(sid, 2, "completed", null, "b", new String[1]));
        assertNotNull(store.update(sid, 3, "completed", null, "c", new String[1]));
        assertEquals(TaskTodoStore.Status.awaiting_confirm, find(store.get(sid), 4).status());
        assertTrue(TaskTodoStore.needsConfirmGate(find(store.get(sid), 4)));
    }

    @Test
    void llmJudge_doneWhen_requiresEvidence() {
        String check = store.verifyCompletion("llm_judge:是否包含层级结构", "");
        assertNotNull(check);
        assertNull(store.verifyCompletion("llm_judge:是否包含层级结构", "电池舱->堆->簇->PACK"));
    }

    @Test
    void builtinSkipsLlmJudge() {
        var v = new BuiltinSemanticTodoValidator();
        var item = new TaskTodoStore.TodoItem(1, "检查", TaskTodoStore.Status.in_progress, "",
                "llm_judge:内容正确", "任意文本", "", List.of());
        assertNull(v.validate(item, "任意文本"));
    }

    private static TaskTodoStore.TodoItem find(List<TaskTodoStore.TodoItem> list, int id) {
        return list.stream().filter(t -> t.id() == id).findFirst().orElseThrow();
    }
}
