package com.miniagent.agent.todo;

import com.miniagent.agent.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TaskTodoConfirmTest {

    @Test
    void generateExcelTitleDoesNotNeedConfirm() {
        var item = new TaskTodoStore.TodoItem(
                1, "生成并发布Excel文件", TaskTodoStore.Status.pending,
                "", "", "", "", List.of());
        assertFalse(TaskTodoStore.needsConfirmGate(item));
    }

    @Test
    void deployToProdNeedsConfirm() {
        var item = new TaskTodoStore.TodoItem(
                1, "发布到生产环境", TaskTodoStore.Status.pending,
                "", "", "", "", List.of());
        assertTrue(TaskTodoStore.needsConfirmGate(item));
    }

    @Test
    void suspendActiveArchivesCompletedPlan(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) {
        TaskTodoStore store = new TaskTodoStore(tmp.toString());
        store.set("s", java.util.List.of(
                java.util.Map.of("id", 1, "content", "定位 dataset", "status", "completed"),
                java.util.Map.of("id", 2, "content", "复制到桌面", "status", "completed")));
        assertTrue(store.hasPlan("s"));
        assertTrue(store.suspendActive("s"));
        assertFalse(store.hasPlan("s"));
        assertTrue(store.get("s").isEmpty());
        assertFalse(store.hasSuspended("s"));
    }

    @Test
    void suspendActiveParksIncompleteForContinue(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) {
        TaskTodoStore store = new TaskTodoStore(tmp.toString());
        store.set("s", java.util.List.of(
                java.util.Map.of("id", 1, "content", "写报告", "status", "in_progress")));
        assertTrue(store.suspendActive("s"));
        assertFalse(store.hasPlan("s"));
        assertTrue(store.hasSuspended("s"));
        assertTrue(store.resumeSuspended("s"));
        assertEquals(TaskTodoStore.Status.in_progress, store.get("s").get(0).status());
    }

    @Test
    void modelTodoToolCannotConfirmForTheUser(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) {
        TaskTodoStore store = new TaskTodoStore(tmp.toString());
        store.set("s", List.of(java.util.Map.of(
                "id", 1,
                "content", "发布到生产环境",
                "status", "awaiting_confirm")));
        ToolRegistry registry = new ToolRegistry();
        TodoTool tool = new TodoTool();
        ReflectionTestUtils.setField(tool, "toolRegistry", registry);
        ReflectionTestUtils.setField(tool, "todoStore", store);
        tool.register();
        TaskTodoContext.set("s");

        try {
            String result = registry.execute(
                    "todo", "{\"action\":\"confirm\",\"id\":1}");
            assertTrue(result.contains("未知 action"));
            assertEquals(
                    TaskTodoStore.Status.awaiting_confirm,
                    store.get("s").get(0).status());
        } finally {
            TaskTodoContext.clear();
        }
    }
}
