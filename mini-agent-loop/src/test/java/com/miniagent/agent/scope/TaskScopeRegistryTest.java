package com.miniagent.agent.scope;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 任务作用域：任务级状态的 key 从哪来、什么时候变、变了之后旧 key 归谁。
 *
 * <p>这里锁死三条对正确性和兼容性最要紧的性质：</p>
 * <ol>
 *   <li><b>0 号任务的 key 就是 sessionId 本身</b> —— 历史数据不用迁移，
 *       且「一个会话一个任务」这条最常见路径上行为与旧实现逐字节相同；</li>
 *   <li><b>开过的 taskId 不复用</b> —— 旧任务的 key 永远指回它自己那份状态。
 *       如果复用，来回切任务就会串台（A 的图被 B 读到），这正是要消灭的那类缺陷；</li>
 *   <li><b>「继续」必须能弹回被挂起的任务</b> —— 否则用户说「继续」时查的是新任务的槽位，
 *       捞得回清单却捞不回规划图。</li>
 * </ol>
 */
class TaskScopeRegistryTest {

    @Test
    void 初始作用域的key等于sessionId_历史数据无需迁移() {
        TaskScopeRegistry registry = new TaskScopeRegistry();
        String sid = "abc123";

        assertEquals(sid, registry.current(sid).scopeKey());
        assertEquals(sid, registry.scopeKey(sid));
        assertEquals(0L, registry.current(sid).taskId());
        assertFalse(registry.scopeKey(sid).contains("#"));
    }

    @Test
    void 边界不动时作用域保持不变() {
        TaskScopeRegistry registry = new TaskScopeRegistry();
        String sid = "abc123";

        TaskScope first = registry.resolve(sid, TaskBoundary.NEW, "prev-plan-suspended");
        TaskScope again = registry.resolve(sid, TaskBoundary.SAME, "same-task");
        assertSame(first, again);
        assertEquals(1L, again.taskId());
    }

    @Test
    void 开新任务只增不减_旧key不被复用() {
        TaskScopeRegistry registry = new TaskScopeRegistry();
        String sid = "abc123";

        String t0 = registry.scopeKey(sid);
        registry.resolve(sid, TaskBoundary.NEW, "prev-plan-suspended");
        String t1 = registry.scopeKey(sid);
        registry.resolve(sid, TaskBoundary.NEW, "prev-plan-suspended");
        String t2 = registry.scopeKey(sid);

        assertEquals(sid, t0);
        assertEquals(sid + "#1", t1);
        assertEquals(sid + "#2", t2);
        assertNotEquals(t0, t1);
        assertNotEquals(t1, t2);
    }

    @Test
    void 继续会换回被离开的那个任务() {
        TaskScopeRegistry registry = new TaskScopeRegistry();
        String sid = "abc123";

        String taskA = registry.scopeKey(sid);
        // 换任务 B：A 移到「刚离开」的位置
        registry.resolve(sid, TaskBoundary.NEW, "prev-plan-suspended");
        String taskB = registry.scopeKey(sid);
        assertEquals(sid + "#1", taskB);

        // 用户说「继续」→ 换回 A
        registry.resolve(sid, TaskBoundary.RESUME, "todo-resumed");
        assertEquals(taskA, registry.scopeKey(sid), "「继续」必须回到被离开的任务 A");

        // 在 A 里再开新任务：发号不复用（否则新任务会撞上 B 的键）
        registry.resolve(sid, TaskBoundary.NEW, "prev-plan-suspended");
        assertEquals(sid + "#2", registry.scopeKey(sid), "任务号必须只增不复用");

        // 「继续」回到刚离开的那个任务 —— 这一步离开的是 A，所以回到 A
        registry.resolve(sid, TaskBoundary.RESUME, "todo-resumed");
        assertEquals(taskA, registry.scopeKey(sid),
                "「继续」只指向最近离开的那个任务；B 已经不是最近离开的了");
    }

    @Test
    void 继续与开新任务互逆_可在两个任务间来回() {
        TaskScopeRegistry registry = new TaskScopeRegistry();
        String sid = "abc123";
        String taskA = registry.scopeKey(sid);

        registry.resolve(sid, TaskBoundary.NEW, "prev-plan-suspended");
        String taskB = registry.scopeKey(sid);

        // A → B → A → B … 交替，每次都回到上一个
        registry.resolve(sid, TaskBoundary.RESUME, "todo-resumed");
        assertEquals(taskA, registry.scopeKey(sid));
        registry.resolve(sid, TaskBoundary.RESUME, "todo-resumed");
        assertEquals(taskB, registry.scopeKey(sid));
        registry.resolve(sid, TaskBoundary.RESUME, "todo-resumed");
        assertEquals(taskA, registry.scopeKey(sid));
    }

    @Test
    void 没有挂起任务时继续不改变作用域() {
        TaskScopeRegistry registry = new TaskScopeRegistry();
        String sid = "abc123";
        assertEquals(sid, registry.scopeKey(sid));
        registry.resolve(sid, TaskBoundary.RESUME, "todo-resumed");
        assertEquals(sid, registry.scopeKey(sid));
        assertEquals(0L, registry.current(sid).taskId());
    }

    @Test
    void 不同会话各自独立计数() {
        TaskScopeRegistry registry = new TaskScopeRegistry();
        registry.resolve("s1", TaskBoundary.NEW, "prev-plan-suspended");
        assertEquals("s1#1", registry.scopeKey("s1"));
        // s2 没切过任务，仍在 0 号槽位，不会撞上 s1 的计数
        assertEquals("s2", registry.scopeKey("s2"));
        registry.resolve("s2", TaskBoundary.NEW, "prev-plan-suspended");
        assertEquals("s2#1", registry.scopeKey("s2"));
    }

    @Test
    void 会话被删除后登记释放_从头开始() {
        TaskScopeRegistry registry = new TaskScopeRegistry();
        String sid = "abc123";
        registry.resolve(sid, TaskBoundary.NEW, "prev-plan-suspended");

        registry.forget(sid);
        assertEquals(0, registry.activeSessionCount());
        assertEquals(sid, registry.scopeKey(sid));
    }

    @Test
    void 空session不登记也不抛错() {
        TaskScopeRegistry registry = new TaskScopeRegistry();
        assertNull(registry.current(null));
        assertNull(registry.current("  "));
        assertNull(registry.resolve(null, TaskBoundary.NEW, "x"));
        assertEquals(0, registry.activeSessionCount());
    }

    @Test
    void 换任务后新任务的key与旧任务不同_状态天然互不可见() {
        TaskScopeRegistry registry = new TaskScopeRegistry();
        String sid = "abc123";
        String scopeA = registry.scopeKey(sid);
        registry.resolve(sid, TaskBoundary.NEW, "prev-plan-suspended");
        String scopeB = registry.scopeKey(sid);

        // 状态按 scopeKey 存放，A 的键与 B 的键不同 → B 查不到 A 的状态
        assertNotEquals(scopeA, scopeB);
        // 两者都以 sessionId 开头，便于按会话清理与排查
        assertTrue(scopeA.startsWith(sid));
        assertTrue(scopeB.startsWith(sid));
        assertEquals(scopeB, sid + "#1");
    }
}
