package com.miniagent.agent.todo;

import com.miniagent.agent.scope.TaskBoundary;
import com.miniagent.agent.scope.TaskScopeRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 清单按任务作用域分键。
 *
 * <p>这是「新任务带上一个任务计划」的结构性修法：清单与规划图用同一个
 * {@code scopeKey}，旧任务的条目留在它自己的键上，新任务读不到 ——
 * 不需要搬数据，也就不需要判断「这一轮算不算换任务」。</p>
 */
class TaskTodoScopeTest {

    private TaskTodoStore store(Path dir, TaskScopeRegistry registry) {
        TaskTodoStore s = new TaskTodoStore(dir.toString());
        s.setScopeRegistry(registry);
        return s;
    }

    private static List<Map<String, Object>> item(String content) {
        return List.of(Map.of("id", 1, "content", content, "status", "pending"));
    }

    @Test
    void 两个任务的清单互不可见(@TempDir Path tmp) {
        TaskScopeRegistry registry = new TaskScopeRegistry();
        TaskTodoStore s = store(tmp, registry);

        s.set("s1", item("任务0-A"));
        assertEquals(1, s.get("s1").size());

        registry.resolve("s1", TaskBoundary.NEW, "test");
        assertTrue(s.get("s1").isEmpty(), "新任务读不到上一个任务的清单");

        s.set("s1", item("任务1-B"));
        assertEquals("任务1-B", s.get("s1").get(0).content());
    }

    @Test
    void 恢复上一个任务能取回它自己的清单(@TempDir Path tmp) {
        TaskScopeRegistry registry = new TaskScopeRegistry();
        TaskTodoStore s = store(tmp, registry);

        s.set("s2", item("任务0-A"));
        registry.resolve("s2", TaskBoundary.NEW, "test");
        s.set("s2", item("任务1-B"));

        assertNotNull(registry.paused("s2"), "离开的任务要留指针");
        List<TaskTodoStore.TodoItem> restored = s.resumePreviousTask("s2");
        assertNotNull(restored);
        assertEquals("任务0-A", restored.get(0).content(), "回到任务0");
        assertEquals("任务0-A", s.get("s2").get(0).content());
    }

    @Test
    void 没有上一个任务时恢复返回null(@TempDir Path tmp) {
        TaskScopeRegistry registry = new TaskScopeRegistry();
        TaskTodoStore s = store(tmp, registry);
        assertNull(s.resumePreviousTask("s3"));
        assertEquals("", s.renderResumableHint("s3"));
    }

    @Test
    void 落盘按任务号分区(@TempDir Path tmp) throws Exception {
        TaskScopeRegistry registry = new TaskScopeRegistry();
        TaskTodoStore s = store(tmp, registry);

        s.set("s4", item("A"));
        registry.resolve("s4", TaskBoundary.NEW, "test");
        s.set("s4", item("B"));

        String json = Files.readString(tmp.resolve("s4.json"), StandardCharsets.UTF_8).trim();
        assertTrue(json.startsWith("{"), "新格式是分区对象，不是裸数组");
        assertTrue(json.contains("\"0\""), "任务0分区要保留");
        assertTrue(json.contains("\"1\""), "任务1分区要写入");
    }

    /** 旧数据是裸数组，读取时整体归 0 号任务，不需要迁移脚本。 */
    @Test
    void 旧数组格式归零号任务(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("s5.json"),
                "[{\"id\":1,\"content\":\"legacy\",\"status\":\"pending\"}]",
                StandardCharsets.UTF_8);

        TaskScopeRegistry registry = new TaskScopeRegistry();
        TaskTodoStore s = store(tmp, registry);
        assertEquals("legacy", s.get("s5").get(0).content());
    }

    @Test
    void clear只清当前任务(@TempDir Path tmp) {
        TaskScopeRegistry registry = new TaskScopeRegistry();
        TaskTodoStore s = store(tmp, registry);

        s.set("s6", item("A"));
        registry.resolve("s6", TaskBoundary.NEW, "test");
        s.set("s6", item("B"));

        s.clear("s6");
        assertTrue(s.get("s6").isEmpty(), "当前任务清空");
        List<TaskTodoStore.TodoItem> restored = s.resumePreviousTask("s6");
        assertNotNull(restored);
        assertEquals("A", restored.get(0).content(), "上一个任务的清单不能被一起抹掉");
    }

    @Test
    void 有未完成历史任务时给出恢复提示(@TempDir Path tmp) {
        TaskScopeRegistry registry = new TaskScopeRegistry();
        TaskTodoStore s = store(tmp, registry);

        s.set("s7", item("A"));
        registry.resolve("s7", TaskBoundary.NEW, "test");

        String hint = s.renderResumableHint("s7");
        assertTrue(hint.contains("action=resume"), "要给出显式入口：" + hint);
        assertTrue(hint.contains("taskId=0"), "要说明恢复到哪个任务：" + hint);

        // 当前任务自己有清单时不提示：两个任务的清单同时出现会混着做
        s.set("s7", item("B"));
        assertEquals("", s.renderResumableHint("s7"));
    }
    /**
     * 收尾不清空：全终态任务推进作用域后，resume 回去仍能看到做过什么。
     *
     * <p>这是「能不能接着做」的关键一环 —— 如果收尾时把清单清了，
     * resume 只能拿到空清单，恢复就成了空操作。</p>
     */
    @Test
    void 全终态任务恢复后仍能看到清单(@TempDir Path tmp) {
        TaskScopeRegistry registry = new TaskScopeRegistry();
        TaskTodoStore s = store(tmp, registry);

        s.set("s8", List.of(Map.of(
                "id", 1, "content", "已完成项", "status", "completed")));
        assertTrue(s.hasOnlyTerminal("s8"));

        // ContextLoader 在全终态时做的事：只推进作用域，不清空清单
        registry.resolve("s8", TaskBoundary.NEW, "prev-plan-terminal");
        assertTrue(s.get("s8").isEmpty(), "新任务从空清单开始");

        List<TaskTodoStore.TodoItem> restored = s.resumePreviousTask("s8");
        assertNotNull(restored);
        assertEquals("已完成项", restored.get(0).content(), "收尾不能清空旧清单");
    }

    /** 全终态的历史任务不提示恢复：没有未完成的事要做。 */
    @Test
    void 全终态历史任务不提示恢复(@TempDir Path tmp) {
        TaskScopeRegistry registry = new TaskScopeRegistry();
        TaskTodoStore s = store(tmp, registry);

        s.set("s9", List.of(Map.of(
                "id", 1, "content", "已完成项", "status", "completed")));
        registry.resolve("s9", TaskBoundary.NEW, "prev-plan-terminal");

        assertEquals("", s.renderResumableHint("s9"));
    }
    /**
     * {@code task.clear} 是作废，不是删除：内容还在，可以 restore 回来。
     *
     * <p>用户点「清空」的意思是「这次不按这份清单做了」，不是「把它彻底抹掉」。
     * 旧实现直接 clear 掉列表，清完就再也拿不回来。</p>
     */
    @Test
    void 清空后可恢复(@TempDir Path tmp) {
        TaskScopeRegistry registry = new TaskScopeRegistry();
        TaskTodoStore s = store(tmp, registry);

        s.set("s10", item("要作废的"));
        s.clear("s10");
        assertTrue(s.get("s10").isEmpty(), "对外表现是清空");

        List<TaskTodoStore.TodoItem> restored = s.restoreCleared("s10");
        assertEquals(1, restored.size());
        assertEquals("要作废的", restored.get(0).content());
        assertEquals(1, s.get("s10").size(), "恢复后重新可见");
    }

    /** 作废状态与内容都要落盘：换一个实例（模拟重启）仍能恢复。 */
    @Test
    void 作废清单落盘后仍可恢复(@TempDir Path tmp) {
        TaskScopeRegistry registry = new TaskScopeRegistry();
        TaskTodoStore first = store(tmp, registry);
        first.set("s11", item("要作废的"));
        first.clear("s11");

        TaskTodoStore second = store(tmp, registry);
        assertTrue(second.get("s11").isEmpty(), "作废状态必须落盘");
        List<TaskTodoStore.TodoItem> restored = second.restoreCleared("s11");
        assertEquals("要作废的", restored.get(0).content(), "作废内容必须还在盘上");
    }

    /** 没被清空过的任务，restore 返回空（不能凭空造清单）。 */
    @Test
    void 没有作废内容时恢复为空(@TempDir Path tmp) {
        TaskScopeRegistry registry = new TaskScopeRegistry();
        TaskTodoStore s = store(tmp, registry);
        s.set("s12", item("正常清单"));
        assertTrue(s.restoreCleared("s12").isEmpty());
        assertEquals(1, s.get("s12").size(), "不能把正常清单当成作废内容");
    }

    /** resume 回到一个被清空过的任务，自动撤销作废标记。 */
    @Test
    void resume回到被作废任务时自动恢复(@TempDir Path tmp) {
        TaskScopeRegistry registry = new TaskScopeRegistry();
        TaskTodoStore s = store(tmp, registry);

        s.set("s13", item("A"));
        s.clear("s13");
        registry.resolve("s13", TaskBoundary.NEW, "test");
        s.set("s13", item("B"));

        List<TaskTodoStore.TodoItem> back = s.resumePreviousTask("s13");
        assertNotNull(back);
        assertEquals("A", back.get(0).content(), "显式回来就该看到原清单");
    }
}
