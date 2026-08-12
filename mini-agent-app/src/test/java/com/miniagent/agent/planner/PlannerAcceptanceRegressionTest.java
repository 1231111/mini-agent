package com.miniagent.agent.planner;

import com.miniagent.agent.intent.IntentType;
import com.miniagent.agent.intent.TaskPlan;
import com.miniagent.agent.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 方案验收固定回归：复杂图依赖推进 / 故意失败恢复 / 并发 CAS / 轻问答不走大图。
 */
class PlannerAcceptanceRegressionTest {

    @Test
    void complexGoalTemplateHasDagAndAdvancesByDeps() {
        GoalCompiler compiler = new GoalCompiler(new PlannerProperties());
        TaskPlan plan = new TaskPlan(
                IntentType.NEW_TASK, "从网页取资料写 md", true, true, true,
                null, List.of(), "complex", true);
        TaskGraph raw = compiler.templateGraph("抓网页并写带图 md", plan);
        assertTrue(raw.nodes().size() >= 3);
        assertTrue(compiler.validate(raw, plan));
        assertFalse(raw.hasCycle());

        TaskGraph g = raw.normalizeForScheduling();
        ReadyTaskSelector sel = new ReadyTaskSelector();
        List<TaskNode> wave1 = sel.select(g);
        assertFalse(wave1.isEmpty());
        TaskNode first = wave1.get(0);
        g = g.replace(first.withStatus(TaskNodeStatus.SUCCESS)).normalizeForScheduling();
        List<TaskNode> wave2 = sel.select(g);
        assertFalse(wave2.isEmpty());
        assertTrue(wave2.stream().noneMatch(n -> n.id().equals(first.id())));
        for (TaskNode n : wave2)
            for (String dep : n.dependsOn())
                assertEquals(TaskNodeStatus.SUCCESS, g.byId(dep).status());
    }

    @Test
    void deliberateToolFailureReplaceToolBumpsVersion() {
        PlannerStateStore store = new PlannerStateStore();
        ToolCapabilityIndex index = new ToolCapabilityIndex(new ToolRegistry());
        index.rebuild();
        RecoveryEngine engine = new RecoveryEngine(store, index, new PlannerProperties());
        Goal goal = new Goal("g", "o", "NEW_TASK", Map.of(), List.of(), List.of());
        String failedTool = "write_file";
        TaskGraph graph = new TaskGraph(List.of(
                new TaskNode("n1", "写文件", "file_write", List.of(), TaskNodeStatus.FAILED,
                        1, "note_required", failedTool, "未知工具: ghost", 0)));
        StateSnapshot s0 = store.init("reg-fail", "exec-fail", goal, graph);
        assertEquals(1L, s0.version());

        FailureDiagnosis dx = engine.diagnose(graph.byId("n1"), failedTool, "未知工具: ghost");
        assertEquals(FailureClass.REPLACE_TOOL, dx.failureClass());
        StateSnapshot s1 = engine.recover("reg-fail", dx).orElseThrow();
        assertEquals(2L, s1.version());
        assertNotEquals(failedTool, s1.graph().byId("n1").toolHint());
        assertEquals(TaskNodeStatus.PENDING, s1.graph().byId("n1").status());
    }

    @Test
    void concurrentCasKeepsHigherVersionWithoutDirtyWrite() throws Exception {
        PlannerStateStore store = new PlannerStateStore();
        Goal goal = new Goal("g", "o", "NEW_TASK", Map.of(), List.of(), List.of());
        TaskGraph graph = new TaskGraph(List.of(
                new TaskNode("n1", "a", "general", List.of(), TaskNodeStatus.PENDING,
                        10, "note_required", "", "", 0)));
        StateSnapshot base = store.init("reg-cas", "exec-cas", goal, graph);

        StateSnapshot aPatch = base.withGraph(graph.replace(
                graph.byId("n1").withStatus(TaskNodeStatus.SUCCESS).withError("writer-a")));
        StateSnapshot bPatch = base.withGraph(graph.replace(
                graph.byId("n1").withStatus(TaskNodeStatus.FAILED).withError("writer-b")));

        AtomicInteger wins = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        Runnable writerA = () -> {
            try {
                store.commit("reg-cas", 1L, aPatch);
                wins.incrementAndGet();
            } catch (PlannerStateStore.VersionConflictException e) {
                conflicts.incrementAndGet();
            }
        };
        Runnable writerB = () -> {
            try {
                store.commit("reg-cas", 1L, bPatch);
                wins.incrementAndGet();
            } catch (PlannerStateStore.VersionConflictException e) {
                conflicts.incrementAndGet();
            }
        };
        Thread t1 = new Thread(writerA);
        Thread t2 = new Thread(writerB);
        t1.start();
        t2.start();
        t1.join();
        t2.join();

        StateSnapshot finalSnap = store.get("reg-cas").orElseThrow();
        assertEquals(2L, finalSnap.version());
        assertEquals(1, wins.get());
        assertEquals(1, conflicts.get());
        String err = finalSnap.graph().byId("n1").lastError();
        assertTrue(err.equals("writer-a") || err.equals("writer-b"));
    }

    @Test
    void lightQuestionSkipsPlannerPath() {
        PlannerProperties props = new PlannerProperties();
        PlanningLoop loop = new PlanningLoop(
                props, null, null, null, null, null, null, null, null, null, null, null);
        TaskPlan question = new TaskPlan(
                IntentType.QUESTION, "你是谁", true, false, false,
                List.of(), List.of(), "qa", false);
        assertFalse(loop.shouldHandle(question));

        TaskPlan review = new TaskPlan(
                IntentType.REVIEW, "回顾一下", true, true, false,
                List.of(), List.of(), "review", false);
        assertFalse(loop.shouldHandle(review));

        TaskPlan complex = new TaskPlan(
                IntentType.NEW_TASK, "复杂交付", true, true, true,
                null, List.of(), "task", true);
        assertTrue(loop.shouldHandle(complex));
    }
}
