package com.miniagent.agent.planner;

import com.miniagent.agent.scope.TaskBoundary;
import com.miniagent.agent.scope.TaskScopeRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「换任务后读不到旧规划图、但说『继续』还能读回来」这条不变量的回归护栏。
 *
 * <p>这是「历史任务污染当前任务」的硬通道：旧实现把规划图挂在 sessionId 上，
 * 于是上一轮那张没做完的图会被这一轮的新问题读到，新问题被旧图的节点调度接管。
 * 现在图按任务作用域键存放 —— 换任务后新任务查的是另一个槽位，
 * 而旧图并没有被删掉，用户说「继续」时作用域弹回去就还能拿到。</p>
 *
 * <p>同时锁住兼容性：0 号任务的键就是 sessionId，所以历史数据和
 * 「一个会话一个任务」这条主线路径行为不变。</p>
 */
class PlannerStateStoreScopeTest {

    private static final Goal GOAL = new Goal(
            "g1", "整理标书", "", Map.of(), List.of(), List.of());
    private static final TaskGraph EMPTY_GRAPH = new TaskGraph(List.of());

    @Test
    void 换任务后新任务读不到旧图_说继续时旧图还在() {
        TaskScopeRegistry registry = new TaskScopeRegistry();
        PlannerStateStore store = new PlannerStateStore(registry);
        String sid = "s1";

        // 任务 A：0 号作用域，键就是 sessionId
        store.init(sid, "e1", GOAL, EMPTY_GRAPH);
        assertTrue(store.get(sid).isPresent(), "0 号任务应当读得到自己的图");
        assertFalse(store.events(sid).isEmpty());

        // 用户换任务：作用域推进到 1
        registry.resolve(sid, TaskBoundary.NEW, "prev-plan-suspended");
        assertTrue(store.get(sid).isEmpty(), "新任务不该读到上一任务的图");
        assertTrue(store.events(sid).isEmpty(), "新任务不该读到上一任务的事件流");

        // 新任务写自己的图，不会覆盖掉旧任务的
        store.init(sid, "e2", GOAL, EMPTY_GRAPH);
        assertEquals(1L, store.get(sid).orElseThrow().version());

        // 用户说「继续」→ 换回任务 A，旧图原样读得回来（而不是新任务 B 的）
        registry.resolve(sid, TaskBoundary.RESUME, "todo-resumed");
        assertTrue(store.get(sid).isPresent(), "「继续」必须能拿回旧任务的图");
        assertEquals("e1", store.get(sid).orElseThrow().executionId(),
                "拿回的必须是旧任务自己的快照，不是新任务的");

        // 在 A 里再开新任务：拿到一个全新的键，A 与 B 的状态都读不到
        registry.resolve(sid, TaskBoundary.NEW, "prev-plan-suspended");
        assertTrue(store.get(sid).isEmpty(), "新任务拿到全新的槽位，不该读到任何旧状态");
    }

    @Test
    void 同一任务内多次读写保持稳定() {
        TaskScopeRegistry registry = new TaskScopeRegistry();
        PlannerStateStore store = new PlannerStateStore(registry);
        String sid = "s1";

        store.init(sid, "e1", GOAL, EMPTY_GRAPH);
        registry.resolve(sid, TaskBoundary.SAME, "same-task");
        assertTrue(store.get(sid).isPresent());
        assertEquals(1L, store.get(sid).orElseThrow().version());
    }

    @Test
    void 没有作用域解析器时退化为按sessionId存取_单测与旧数据兼容() {
        PlannerStateStore store = new PlannerStateStore();
        store.init("s1", "e1", GOAL, EMPTY_GRAPH);
        assertTrue(store.get("s1").isPresent());
        assertTrue(store.get("s2").isEmpty());
    }
}
