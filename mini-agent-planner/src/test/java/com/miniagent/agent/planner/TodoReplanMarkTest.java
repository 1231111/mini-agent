package com.miniagent.agent.planner;

import com.miniagent.agent.todo.TaskTodoStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 右侧检查器要靠 todo 上的两个标记才能画出「重跑 ×N / 重规划 RN」，
 * 这里锁住投影和状态搬迁时标记不会丢。
 */
class TodoReplanMarkTest {

    @Test
    void replanGenerationReadsGraphIdPrefix() {
        assertEquals(1, TodoStateProjector.replanGeneration("rp1_node3"));
        assertEquals(2, TodoStateProjector.replanGeneration("rp2_a"));
        assertEquals(0, TodoStateProjector.replanGeneration("node1"));
        assertEquals(0, TodoStateProjector.replanGeneration("rp_node1"));
        assertEquals(0, TodoStateProjector.replanGeneration("rpX_node1"));
        assertEquals(0, TodoStateProjector.replanGeneration(null));
    }

    @Test
    void legacyConstructorKeepsMarksAtZero() {
        TaskTodoStore.TodoItem item = new TaskTodoStore.TodoItem(1, "读文件",
                TaskTodoStore.Status.pending, "");
        assertEquals(0, item.retryCount());
        assertEquals(0, item.replanGeneration());
    }

    @Test
    void statusChangeKeepsRerunAndReplanMarks() {
        TaskTodoStore.TodoItem item = new TaskTodoStore.TodoItem(2, "解析 DAG",
                TaskTodoStore.Status.in_progress, "上次失败",
                "", "", "", List.of(1), 2, 1);
        TaskTodoStore.TodoItem blocked = item.withStatus(TaskTodoStore.Status.blocked, "上游未完成");
        assertEquals(2, blocked.retryCount());
        assertEquals(1, blocked.replanGeneration());
        assertTrue(blocked.dependsOn().contains(1));
        assertEquals("上游未完成", blocked.note());
    }

    @Test
    void negativeMarksAreClamped() {
        TaskTodoStore.TodoItem item = new TaskTodoStore.TodoItem(3, "x",
                TaskTodoStore.Status.pending, "", "", "", "", List.of(), -3, -1);
        assertEquals(0, item.retryCount());
        assertEquals(0, item.replanGeneration());
    }
}
