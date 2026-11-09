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

    /**
     * 全部终态 = 任务客观结束，但清单<b>保留</b>。
     *
     * <p>收尾只推进作用域键，不清空条目 —— 用户之后 resume 回来还要看到做过什么。
     * 之前那版在这里清空，结果 resume 只能拿到空清单。</p>
     */
    @Test
    void 全终态判定为真但清单保留(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) {
        TaskTodoStore store = new TaskTodoStore(tmp.toString());
        store.set("s", java.util.List.of(
                java.util.Map.of("id", 1, "content", "定位 dataset", "status", "completed"),
                java.util.Map.of("id", 2, "content", "复制到桌面", "status", "completed")));
        assertTrue(store.hasPlan("s"));
        assertTrue(store.hasOnlyTerminal("s"));
        assertEquals(2, store.get("s").size(), "收尾不清空清单");
    }

    /**
     * 未完成的清单**不**归档、也不"存起来等下次捞"。
     *
     * <p>旧实现把它压进 suspended_json，下一轮只要看到有挂起物就自动恢复 ——
     * 于是新任务被上一个任务的清单接管。现在它原地留在当前任务里，
     * 要不要放弃由模型 task.clear / task.set 决定。</p>
     */
    @Test
    void incompletePlanStaysInPlaceAndIsNotArchived(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) {
        TaskTodoStore store = new TaskTodoStore(tmp.toString());
        store.set("s", java.util.List.of(
                java.util.Map.of("id", 1, "content", "写报告", "status", "in_progress")));
        assertFalse(store.hasOnlyTerminal("s"), "未完成不算终态");
        assertEquals(1, store.get("s").size());
        assertEquals(TaskTodoStore.Status.in_progress, store.get("s").get(0).status());
    }

    /** 空清单不算终态，否则每一轮都会白推一个新任务号。 */
    @Test
    void emptyPlanIsNotTerminal(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) {
        TaskTodoStore store = new TaskTodoStore(tmp.toString());
        assertFalse(store.hasOnlyTerminal("s"));
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
    /**
     * 历史遗留的 suspended_json 不再复活。
     *
     * <p>旧实现把它当成「上次没做完、这轮接着做」，于是每一轮读回来 ——
     * 新任务被上一个任务的清单接管就是这么来的。现在只认 active_json。</p>
     */
    @Test
    void legacySuspendedJsonIsNeverResurrected(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) {
        TaskTodoStore store = new TaskTodoStore(tmp.toString());
        store.setPersistence(new SessionTodoPersistence() {
            @Override
            public State load(String sessionId) {
                return new State("[]",
                        "[{\"id\":1,\"content\":\"上一任务的航班查询\",\"status\":\"pending\"}]");
            }

            @Override
            public void save(String sessionId, String activeJson, String suspendedJson) {
            }

            @Override
            public void delete(String sessionId) {
            }
        });

        assertTrue(store.get("s").isEmpty(), "只读 active，不读 suspended");
        assertFalse(store.hasPlan("s"));
        assertFalse(store.hasOnlyTerminal("s"), "空清单不是终态");
    }

    /** 写清单时 suspended_json 恒为 null：挂起机制已删除，不该再往库里写它。 */
    @Test
    void saveNeverWritesSuspendedJson(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) {
        final java.util.concurrent.atomic.AtomicReference<String> active =
                new java.util.concurrent.atomic.AtomicReference<>("[]");
        final java.util.concurrent.atomic.AtomicReference<String> suspended =
                new java.util.concurrent.atomic.AtomicReference<>("unset");
        TaskTodoStore store = new TaskTodoStore(tmp.toString());
        store.setPersistence(new SessionTodoPersistence() {
            @Override
            public State load(String sessionId) {
                // 用真实回读：store 每次读都会走持久化，桩必须把写进去的东西吐出来，
                // 否则断言测的是桩的返回值，不是 store 的行为。
                return new State(active.get(), null);
            }

            @Override
            public void save(String sessionId, String activeJson, String suspendedJson) {
                active.set(activeJson);
                suspended.set(suspendedJson);
            }

            @Override
            public void delete(String sessionId) {
            }
        });

        store.set("s", List.of(java.util.Map.of(
                "id", 1, "content", "写报告", "status", "pending")));

        assertNull(suspended.get(), "suspended_json 必须写 null");
        assertEquals(1, store.get("s").size());
    }
}
