package com.miniagent.agent.planner;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 意图必须跟着规划状态一起持久化。
 *
 * <p>此前 {@code StateSnapshot} 只存 goal 和 graph，续跑时 intent 由
 * {@code TaskIntent.fromGoal(goal)} 重建，deliverables / exclusions 全部丢失，
 * 语义校验在续跑那一轮等于没跑。这里锁住三条：init 落盘、图更新不丢、
 * JSON 往返与旧快照兼容。</p>
 */
class TaskIntentPersistenceTest {

    private static final Goal GOAL = new Goal("g1", "整理标书并输出评分表", "",
            Map.of(), List.of("不得改动原件"), List.of("评分表存在"));

    private static final TaskIntent INTENT = new TaskIntent(
            "整理标书并输出评分表",
            List.of(new TaskIntent.Deliverable("评分表", "xlsx", "score.xlsx", true)),
            List.of(new TaskIntent.Input("投标文件", "upload", true)),
            List.of("不得改动原件"),
            List.of("删除原文件"),
            List.of("评分表存在且可打开"),
            false, "");

    @Test
    void initKeepsIntentWithDeliverables() {
        PlannerStateStore store = new PlannerStateStore();
        store.init("s1", "e1", GOAL, new TaskGraph(List.of()), INTENT);

        TaskIntent stored = store.get("s1").orElseThrow().intent();
        assertEquals(1, stored.deliverables().size());
        assertEquals("score.xlsx", stored.deliverables().get(0).path());
        assertEquals(List.of("删除原文件"), stored.exclusions());
    }

    @Test
    void graphUpdateAndCommitDoNotDropIntent() {
        PlannerStateStore store = new PlannerStateStore();
        TaskGraph initial = new TaskGraph(List.of(writerNode()));
        StateSnapshot snap = store.init("s1", "e1", GOAL, initial, INTENT);

        StateSnapshot committed = store.commit("s1", snap.version(),
                snap.withGraph(initial.replace(writerNode().withStatus(TaskNodeStatus.RUNNING))));

        assertEquals("score.xlsx", committed.intent().deliverables().get(0).path());
        assertEquals(List.of("删除原文件"), committed.intent().exclusions());
    }

    @Test
    void jsonRoundTripKeepsIntentAndLegacyJsonFallsBackToGoal() {
        StateSnapshot snap = new StateSnapshot(1L, "s1", "e1", GOAL, new TaskGraph(List.of()),
                Map.of(), Map.of(), List.of(), 0, PlanRevision.initial("t"), INTENT);

        StateSnapshot decoded = PlannerStateJson.snapshotFromJson(
                PlannerStateJson.snapshotToJson(snap));
        assertEquals("score.xlsx", decoded.intent().deliverables().get(0).path());

        ObjectNode legacy = (ObjectNode) PlannerStateJson.MAPPER.valueToTree(snap);
        legacy.remove("intent");
        StateSnapshot fromLegacy = PlannerStateJson.snapshotFromJson(legacy.toString());
        assertEquals(GOAL.objective(), fromLegacy.intent().objective());
        assertTrue(fromLegacy.intent().deliverables().isEmpty());
    }

    @Test
    void resumedSnapshotSourceIsNotMarkedDegraded() {
        GoalCompiler.CompileResult resumed = new GoalCompiler.CompileResult(
                GOAL, new TaskGraph(List.of()), false,
                GoalCompiler.CompilationSource.RESUMED, "", INTENT);
        assertFalse(resumed.degraded());
    }

    @Test
    void degradedSourceAndFlagSurviveApprovalCommitAndJsonRoundTrip() {
        PlannerStateStore store = new PlannerStateStore();
        StateSnapshot initial = store.init("s1", "e1", GOAL,
                new TaskGraph(List.of(writerNode())), INTENT,
                GoalCompiler.CompilationSource.MODEL_ERROR, true);

        Map<String, Object> exec = new HashMap<>(initial.execution());
        exec.put(PlannerStateStore.EXECUTION_PLAN_APPROVAL,
                PlannerStateStore.PLAN_APPROVAL_PENDING);
        StateSnapshot pending = store.commit("s1", initial.version(), initial.withExecution(exec));

        Map<String, Object> approvedExec = new HashMap<>(pending.execution());
        approvedExec.put(PlannerStateStore.EXECUTION_PLAN_APPROVAL,
                PlannerStateStore.PLAN_APPROVAL_APPROVED);
        StateSnapshot approved = store.commit("s1", pending.version(), pending.withExecution(approvedExec));

        assertTrue(approved.degraded());
        assertEquals(GoalCompiler.CompilationSource.MODEL_ERROR,
                approved.compilationSource());

        StateSnapshot decoded = PlannerStateJson.snapshotFromJson(
                PlannerStateJson.snapshotToJson(approved));
        assertTrue(decoded.degraded());
        assertEquals(GoalCompiler.CompilationSource.MODEL_ERROR,
                decoded.compilationSource());
    }

    @Test
    void pendingPlanApprovalIsDetectableAfterCommit() {
        PlannerStateStore store = new PlannerStateStore();
        StateSnapshot snap = store.init("s1", "e1", GOAL,
                new TaskGraph(List.of(writerNode())), INTENT);

        Map<String, Object> exec = new HashMap<>(snap.execution());
        exec.put(PlannerStateStore.EXECUTION_PLAN_APPROVAL,
                PlannerStateStore.PLAN_APPROVAL_PENDING);
        store.commit("s1", snap.version(), snap.withExecution(exec));

        assertTrue(store.hasPendingPlanApproval("s1"));

        StateSnapshot approved = store.get("s1").orElseThrow();
        Map<String, Object> next = new HashMap<>(approved.execution());
        next.put(PlannerStateStore.EXECUTION_PLAN_APPROVAL,
                PlannerStateStore.PLAN_APPROVAL_APPROVED);
        store.commit("s1", approved.version(), approved.withExecution(next));

        assertFalse(store.hasPendingPlanApproval("s1"));
    }

    @Test
    void modelFailureIsDegradedButSuccessfulModelCompilationIsNot() {
        GoalCompiler.CompileResult model = new GoalCompiler.CompileResult(
                GOAL, new TaskGraph(List.of()), false,
                GoalCompiler.CompilationSource.MODEL, "", INTENT);
        GoalCompiler.CompileResult modelError = new GoalCompiler.CompileResult(
                GOAL, new TaskGraph(List.of()), true,
                GoalCompiler.CompilationSource.MODEL_ERROR, "MODEL_ERROR", INTENT);

        assertFalse(model.degraded(), "模型编译成功不算降级");
        assertTrue(modelError.degraded(), "模型编译失败必须标记降级");
    }

    private static TaskNode writerNode() {
        return new TaskNode("write", "写入 score.xlsx", "file_write",
                List.of(), List.of(), List.of("score.xlsx"), TaskNodeStatus.PENDING,
                1, DoneWhen.file("score.xlsx"), "", "", 0, "");
    }
}
