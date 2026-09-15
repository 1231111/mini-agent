package com.miniagent.agent.todo;

import org.junit.jupiter.api.Test;

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
}
