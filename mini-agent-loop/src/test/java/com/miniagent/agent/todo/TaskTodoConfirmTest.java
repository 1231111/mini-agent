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

    /**
     * 全自动（自动编辑 + 自动确认）生效后，因危险步闸门停在 awaiting_confirm 的条目要自己放开。
     *
     * <p>这类条目是模型**已经停下来等人**的状态。用户中途打开两个开关，如果不放开，
     * 它就永远停在那里等一个不会来的点击 —— 也就是"开了自动还在等确认"。</p>
     */
    @Test
    void fullAutoReleasesConfirmGatedItems(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) {
        TaskTodoStore store = new TaskTodoStore(tmp.toString());
        store.set("s", List.of(java.util.Map.of(
                "id", 1,
                "content", "发布到生产环境",
                "status", "awaiting_confirm")));

        assertEquals(1, store.releaseConfirmGatedItems("s"));
        assertEquals(TaskTodoStore.Status.in_progress, store.get("s").get(0).status());
        assertTrue(store.get("s").get(0).note().contains("FULL_AUTO"),
                "放开要留痕，事后能看出是自动放行而不是用户点的");
        assertEquals(0, store.releaseConfirmGatedItems("s"), "重复调用不应重复放开");
    }

    /**
     * 「缺密钥 / 须用户补充信息」同样写 awaiting_confirm，全自动也不能替用户回答。
     *
     * <p>放开这类条目只会让模型在缺输入的情况下空转。所以放开只认危险步那一类，
     * 判定沿用 needsConfirmGate 的同一个正则。</p>
     */
    @Test
    void fullAutoDoesNotReleaseHumanInputItems(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) {
        TaskTodoStore store = new TaskTodoStore(tmp.toString());
        store.set("s", List.of(java.util.Map.of(
                "id", 1,
                "content", "等待用户提供 API Key",
                "status", "awaiting_confirm")));

        assertEquals(0, store.releaseConfirmGatedItems("s"));
        assertEquals(TaskTodoStore.Status.awaiting_confirm, store.get("s").get(0).status());
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
