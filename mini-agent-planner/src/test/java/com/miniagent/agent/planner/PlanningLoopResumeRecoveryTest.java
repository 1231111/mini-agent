package com.miniagent.agent.planner;

import com.miniagent.agent.core.AgentLoop;
import com.miniagent.agent.core.NodeExecutor;
import com.miniagent.agent.task.TaskPlan;
import com.miniagent.agent.tool.CapabilityRegistry;
import com.miniagent.agent.tool.ToolErrorCode;
import com.miniagent.agent.tool.ToolResult;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlanningLoopResumeRecoveryTest {

    @Test
    void nodeToolBudgetIsDurableAcrossResumesAndScopedToPlanVersion() {
        String sessionId = "s_node_tool_budget";
        String executionId = "exec_node_tool_budget";
        Goal goal = new Goal("goal", "制作页面", "", "code", Map.of(), List.of(), List.of());
        TaskGraph graph = new TaskGraph(List.of(new TaskNode("page", "实现页面", "file_write",
                List.of(), List.of(), List.of("index.html"), TaskNodeStatus.PENDING,
                90, DoneWhen.file("index.html"), "", "", 0, "")));
        PlannerStateStore store = new PlannerStateStore();
        StateSnapshot snapshot = store.init(sessionId, executionId, goal, graph);
        TaskGraph running = snapshot.graph().replace(
                snapshot.graph().byId("page").withStatus(TaskNodeStatus.RUNNING));
        snapshot = store.commit(sessionId, snapshot.version(), snapshot.withGraph(running));

        PlannerStateStore.NodeToolCallReservation first = store.reserveNodeToolCall(
                sessionId, executionId, snapshot.planVersion(), "page", 2);
        PlannerStateStore.NodeToolCallReservation second = store.reserveNodeToolCall(
                sessionId, executionId, snapshot.planVersion(), "page", 2);
        // Simulate a new PlanningLoop instance handling an explicit continue: it reads
        // the same durable state rather than rebuilding a per-run in-memory counter.
        StateSnapshot resumed = store.get(sessionId).orElseThrow();
        PlannerStateStore.NodeToolCallReservation exhausted = store.reserveNodeToolCall(
                sessionId, executionId, resumed.planVersion(), "page", 2);

        assertThat(first.reserved()).isTrue();
        assertThat(second.reserved()).isTrue();
        assertThat(exhausted.reserved()).isFalse();
        assertThat(exhausted.callsUsed()).isEqualTo(2);
        assertThat(exhausted.reason()).contains("工具调用上限 2");
        assertThat(resumed.execution().get(PlannerStateStore.EXECUTION_NODE_TOOL_BUDGETS))
                .isInstanceOf(Map.class)
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry(snapshot.planVersion() + ":page", 2);
        assertThat(store.events(sessionId)).filteredOn(event ->
                event.type() == DomainEventType.NODE_TOOL_CALL_RESERVED)
                .hasSize(2)
                .allSatisfy(event -> assertThat(event.payload()).containsKeys(
                        "planVersion", "callsUsed", "maxCalls"));
    }

    @Test
    void zeroNodeToolBudgetDoesNotTurnToolCountIntoTaskFailure() {
        String sessionId = "s_node_tool_budget_unlimited";
        String executionId = "exec_node_tool_budget_unlimited";
        Goal goal = new Goal("goal", "读取并核验资料", "", "research", Map.of(), List.of(), List.of());
        TaskNode node = new TaskNode("research", "读取并核验资料", "research", List.of(), List.of(),
                List.of("facts"), TaskNodeStatus.RUNNING, 90, DoneWhen.note(), "", "", 0, "");
        PlannerStateStore store = new PlannerStateStore();
        StateSnapshot snapshot = store.init(sessionId, executionId, goal, new TaskGraph(List.of(node)));

        for (int call = 0; call < 200; call++) {
            PlannerStateStore.NodeToolCallReservation reservation = store.reserveNodeToolCall(
                    sessionId, executionId, snapshot.planVersion(), "research", 0);
            assertThat(reservation.reserved()).isTrue();
            snapshot = store.get(sessionId).orElseThrow();
        }
        assertThat(store.nodeToolCallsUsed(snapshot, "research")).isEqualTo(200);
        assertThat(store.events(sessionId)).filteredOn(event ->
                event.type() == DomainEventType.NODE_TOOL_CALL_RESERVED).hasSize(200);
    }

    @Test
    void explicitContinueGetsOneReplanForCancelledPrerequisiteAfterAutomaticRecoveryBudgetIsExhausted() {
        TaskGraph graph = blockedGraph();

        assertThat(PlanningLoop.shouldAttemptCancelledDependencyResume(true, false, graph)).isTrue();
        assertThat(PlanningLoop.shouldAttemptCancelledDependencyResume(true, true, graph)).isFalse();
        assertThat(PlanningLoop.shouldAttemptCancelledDependencyResume(false, false, graph)).isFalse();
        assertThat(PlanningLoop.replanBudgetAvailable(3, 3, 2, 2,
                FailureKind.GENERIC, false)).isFalse();
        assertThat(PlanningLoop.replanBudgetAvailable(3, 3, 2, 2,
                FailureKind.DEPENDENCY_FAILED, true)).isTrue();
        assertThat(PlanningLoop.replanBudgetAvailable(3, 3, 2, 2,
                FailureKind.TOOL_BUDGET_EXHAUSTED, true)).isTrue();
        assertThat(PlanningLoop.replanBudgetAvailable(3, 3, 2, 2,
                FailureKind.GENERIC, true)).isFalse();
    }

    @Test
    void zeroChunkLimitContinuesAfterPerSegmentLoopGuardUntilOtherSafetyStop() {
        assertThat(PlanningLoop.shouldResumeChunk(AgentLoop.LOOP_MAX_ITERATIONS, 1, 0)).isTrue();
        assertThat(PlanningLoop.shouldResumeChunk(AgentLoop.LOOP_MAX_ITERATIONS, 50, 0)).isTrue();
        assertThat(PlanningLoop.shouldResumeChunk(AgentLoop.LOOP_MAX_ITERATIONS, 5, 6)).isTrue();
        assertThat(PlanningLoop.shouldResumeChunk(AgentLoop.LOOP_MAX_ITERATIONS, 6, 6)).isFalse();
        assertThat(PlanningLoop.shouldResumeChunk(AgentLoop.NO_PROGRESS, 1, 0)).isFalse();
    }

    @Test
    void budgetRecoveryAllowanceIsDurableAndBoundedAcrossPlanVersions() {
        String sessionId = "s_tool_budget_recovery_total";
        String executionId = "exec_tool_budget_recovery_total";
        Goal goal = new Goal("goal", "完成任务", "", "code", Map.of(), List.of(), List.of());
        TaskNode first = new TaskNode("first", "检查输入", "research", List.of(), List.of(),
                List.of("input-facts"), TaskNodeStatus.RUNNING, 90, DoneWhen.note(), "", "", 0, "");
        PlannerStateStore store = new PlannerStateStore();
        StateSnapshot snapshot = store.init(sessionId, executionId, goal, new TaskGraph(List.of(first)));
        assertThat(store.reserveNodeToolCall(sessionId, executionId, snapshot.planVersion(), "first", 1)
                .reserved()).isTrue();
        snapshot = store.get(sessionId).orElseThrow();
        snapshot = store.commit(sessionId, snapshot.version(), snapshot.withGraph(snapshot.graph().replace(
                snapshot.graph().byId("first").withStatus(TaskNodeStatus.FAILED))));

        PlannerStateStore.NodeToolBudgetRecoveryReservation firstRecovery = store.reserveNodeToolBudgetRecovery(
                sessionId, executionId, snapshot.planVersion(), "first", 1, 2, 1);
        assertThat(firstRecovery.reserved()).isTrue();
        StateSnapshot revised = firstRecovery.snapshot().revisePlan(new TaskGraph(List.of(
                new TaskNode("second", "核验已有产物", "research", List.of(), List.of(),
                        List.of("verified"), TaskNodeStatus.RUNNING, 90, DoneWhen.note(), "", "", 0, ""))),
                goal, "test_new_generation");
        revised = store.commit(sessionId, firstRecovery.snapshot().version(), revised);
        assertThat(store.reserveNodeToolCall(sessionId, executionId, revised.planVersion(), "second", 1)
                .reserved()).isTrue();
        StateSnapshot secondSpent = store.get(sessionId).orElseThrow();
        secondSpent = store.commit(sessionId, secondSpent.version(), secondSpent.withGraph(
                secondSpent.graph().replace(secondSpent.graph().byId("second")
                        .withStatus(TaskNodeStatus.FAILED))));

        PlannerStateStore.NodeToolBudgetRecoveryReservation overTaskLimit = store.reserveNodeToolBudgetRecovery(
                sessionId, executionId, secondSpent.planVersion(), "second", 1, 2, 1);

        assertThat(overTaskLimit.reserved()).isFalse();
        assertThat(overTaskLimit.reason()).contains("跨计划版本");
        assertThat(store.get(sessionId).orElseThrow().execution())
                .containsEntry(PlannerStateStore.EXECUTION_TOOL_BUDGET_RECOVERY_ATTEMPTS_TOTAL, 1);
    }

    @Test
    void concurrentBudgetRecoveryReservationsCannotOverrunTheDurableTaskCap() throws Exception {
        String sessionId = "s_tool_budget_recovery_concurrent";
        String executionId = "exec_tool_budget_recovery_concurrent";
        Goal goal = new Goal("goal", "完成任务", "", "code", Map.of(), List.of(), List.of());
        TaskNode node = new TaskNode("node", "检查输入", "research", List.of(), List.of(),
                List.of("facts"), TaskNodeStatus.RUNNING, 90, DoneWhen.note(), "", "", 0, "");
        PlannerStateStore store = new PlannerStateStore();
        StateSnapshot initial = store.init(sessionId, executionId, goal, new TaskGraph(List.of(node)));
        assertThat(store.reserveNodeToolCall(sessionId, executionId, initial.planVersion(), "node", 1)
                .reserved()).isTrue();
        StateSnapshot spent = store.get(sessionId).orElseThrow();
        spent = store.commit(sessionId, spent.version(), spent.withGraph(spent.graph().replace(
                spent.graph().byId("node").withStatus(TaskNodeStatus.FAILED))));
        long planVersion = spent.planVersion();

        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<PlannerStateStore.NodeToolBudgetRecoveryReservation> first = pool.submit(() ->
                    store.reserveNodeToolBudgetRecovery(sessionId, executionId, planVersion,
                            "node", 1, 2, 1));
            Future<PlannerStateStore.NodeToolBudgetRecoveryReservation> second = pool.submit(() ->
                    store.reserveNodeToolBudgetRecovery(sessionId, executionId, planVersion,
                            "node", 1, 2, 1));

            List<PlannerStateStore.NodeToolBudgetRecoveryReservation> reservations =
                    List.of(first.get(), second.get());
            assertThat(reservations).filteredOn(PlannerStateStore.NodeToolBudgetRecoveryReservation::reserved)
                    .hasSize(1);
        }

        StateSnapshot persisted = store.get(sessionId).orElseThrow();
        assertThat(persisted.execution())
                .containsEntry(PlannerStateStore.EXECUTION_TOOL_BUDGET_RECOVERY_ATTEMPTS_TOTAL, 1);
        assertThat(store.events(sessionId)).filteredOn(event ->
                event.type() == DomainEventType.NODE_TOOL_BUDGET_RECOVERY_RESERVED).hasSize(1);
    }

    @Test
    void preDispatchRunBudgetStopIsNotMisclassifiedAsAnUnknownSideEffect() {
        ToolResult deniedBeforeHandler = ToolResult.failure(
                ToolErrorCode.RATE_LIMITED, "任务已耗尽工具调用配额", false);
        ToolResult unknownSideEffect = ToolResult.unknown("写入结果未确认", null);

        assertThat(PlanningLoop.hasUnknownToolOutcome(deniedBeforeHandler, true,
                com.miniagent.agent.core.ExecutionControl.StopReason.TOOL_BUDGET_EXCEEDED, false)).isFalse();
        assertThat(PlanningLoop.hasUnknownToolOutcome(unknownSideEffect, true,
                com.miniagent.agent.core.ExecutionControl.StopReason.TOOL_BUDGET_EXCEEDED, false)).isTrue();
        assertThat(PlanningLoop.hasUnknownToolOutcome(deniedBeforeHandler, true,
                com.miniagent.agent.core.ExecutionControl.StopReason.TOOL_BUDGET_EXCEEDED, true)).isTrue();
        // Without a typed control receipt, keep the conservative legacy behavior.
        assertThat(PlanningLoop.hasUnknownToolOutcome(deniedBeforeHandler, true,
                com.miniagent.agent.core.ExecutionControl.StopReason.NONE, false)).isTrue();
    }

    @Test
    void persistedPartialExecutionEvidenceRequiresReadOnlyReconciliationBeforeResume() {
        TaskNode stopped = new TaskNode("page", "完成页面改造", "code", List.of(), List.of(),
                List.of("index.html"), TaskNodeStatus.PENDING, 90, DoneWhen.file("index.html"),
                "", "", 0, "本节点工具执行记录：write_file(index.html) -> success；exec_command(npm test) -> denied");

        String evidence = PlanningLoop.previousNodeExecutionEvidence(stopped);

        assertThat(evidence).contains("write_file(index.html)");
        assertThat(evidence).hasSizeLessThanOrEqualTo(6_001);
        assertThat(PlanningLoop.focusBlock(new ActionProposal("proposal", 1, 1, "exec",
                        List.of(new ActionSpec("action", "page", "write_file", Map.of(),
                                "note_required", ""))),
                new TaskGraph(List.of(stopped)), java.util.Set.of(), true))
                .contains("以上记录只用于了解已发生的操作")
                .contains("先用只读方式核验外部状态")
                .contains("不得盲目重放可能已成功的副作用");
    }

    @Test
    void cancelledPrerequisiteAndPendingDescendantAreRecognizedWithoutTouchingSuccessfulReceipt() {
        TaskGraph graph = blockedGraph();

        assertThat(PlanningLoop.shouldAttemptCancelledDependencyResume(true, false, graph)).isTrue();
        assertThat(graph.byId("done").status()).isEqualTo(TaskNodeStatus.SUCCESS);
        assertThat(graph.byId("cancelled").status()).isEqualTo(TaskNodeStatus.CANCELLED);
        assertThat(graph.byId("blocked").status()).isEqualTo(TaskNodeStatus.PENDING);
    }

    @Test
    void replacementGraphKeepsSuccessfulReceiptsAndReplacesCancelledBranch() {
        TaskGraph oldGraph = blockedGraph();
        TaskGraph replanned = new TaskGraph(List.of(
                new TaskNode("verify", "只读核验现有页面文件", "file_read", List.of(),
                        List.of("index.html"), List.of("current-page-state"), TaskNodeStatus.PENDING,
                        90, DoneWhen.note(), "", "", 0, ""),
                new TaskNode("tests", "重建页面结构测试", "code", List.of("verify"),
                        List.of("current-page-state"), List.of("structure.test.js"),
                        TaskNodeStatus.PENDING, 80, DoneWhen.file("structure.test.js"), "", "", 0, ""),
                new TaskNode("run", "运行页面结构测试", "shell", List.of("tests"),
                        List.of("structure.test.js"), List.of("node --test structure.test.js"),
                        TaskNodeStatus.PENDING, 70, DoneWhen.command("node --test structure.test.js"),
                        "", "", 0, "")));

        TaskGraph merged = PlanningLoop.mergeKeepSuccess(oldGraph, replanned, 4);

        assertThat(merged).isNotNull();
        assertThat(merged.byId("done").status()).isEqualTo(TaskNodeStatus.SUCCESS);
        assertThat(merged.byId("cancelled")).isNull();
        assertThat(merged.byId("blocked")).isNull();
        assertThat(merged.nodes()).extracting(TaskNode::id)
                .contains("rp4_verify", "rp4_tests", "rp4_run");
        assertThat(merged.byId("rp4_verify").dependsOn()).contains("done");
        assertThat(merged.byId("rp4_run").dependsOn()).contains("rp4_tests");
        assertThat(merged.nodes()).filteredOn(node -> !node.id().equals("done"))
                .allSatisfy(node -> assertThat(node.status()).isEqualTo(TaskNodeStatus.PENDING));
    }

    @Test
    void resumedCompilerRequestRestoresOriginalGoalAndPreservesUserSteering() {
        Goal goal = new Goal("goal", "制作页面并运行验收测试", "", "code", Map.of(), List.of(), List.of());
        TaskIntent intent = new TaskIntent("制作页面并运行验收测试", List.of(), List.of(),
                List.of("不得覆盖用户已有文件"), List.of(), List.of(), false, "");
        StateSnapshot snapshot = new StateSnapshot(23, "s_test", "exec_test", goal, blockedGraph(),
                Map.of(), Map.of(), List.of(), 3, PlanRevision.initial("test"), intent);

        String request = PlanningLoop.cancelledDependencyResumeRequest(snapshot, "继续");

        assertThat(request).contains("制作页面并运行验收测试", "继续未完成任务", "继续",
                "遵循已保存意图与原始约束", "已成功节点不得重做");
    }

    @Test
    void resumeSummaryDoesNotMisreportCancelledNodeAsMerelyPending() {
        Goal goal = new Goal("goal", "制作页面并运行验收测试", "", "code", Map.of(), List.of(), List.of());
        StateSnapshot snapshot = new StateSnapshot(23, "s_test", "exec_test", goal, blockedGraph(),
                Map.of(), Map.of(), List.of(), 3, PlanRevision.initial("test"), null);

        String summary = PlanningLoop.buildContinueTaskSummary(snapshot, "继续");

        assertThat(summary).contains("1/3 已完成", "1 个因恢复耗尽取消（继续时需重新规划）",
                "1 个待执行", "已尝试恢复次数：3");
    }

    @Test
    void explicitContinueActuallyReplansCancelledDependencyInsteadOfReturningDependencyFailed() {
        String sessionId = "s_resume_cancelled_dependency";
        String executionId = "exec_resume_cancelled_dependency";
        Goal originalGoal = new Goal("goal", "制作页面并运行验收测试", "", "code",
                Map.of(), List.of(), List.of());
        TaskIntent originalIntent = new TaskIntent(originalGoal.objective(), List.of(), List.of(),
                List.of("不得覆盖用户已有文件"), List.of(), List.of(), false, "");
        PlannerStateStore stateStore = new PlannerStateStore();
        StateSnapshot snapshot = stateStore.init(sessionId, executionId, originalGoal,
                blockedGraph(), originalIntent);
        for (int i = 0; i < 3; i++) {
            snapshot = stateStore.commit(sessionId, snapshot.version(), snapshot.withRecoveryInc());
        }

        String question = "请补充页面验收标准";
        Goal clarificationGoal = new Goal("goal", originalGoal.objective(), "",
                Goal.TASK_TYPE_CLARIFY, Map.of(), List.of(), List.of());
        TaskIntent clarificationIntent = new TaskIntent(originalGoal.objective(), List.of(), List.of(),
                originalIntent.constraints(), List.of(), List.of(), true, question);
        GoalCompiler.CompileResult clarification = new GoalCompiler.CompileResult(
                clarificationGoal, GoalCompiler.clarifyGraph(question), false,
                GoalCompiler.CompilationSource.MODEL, "", clarificationIntent);

        GoalCompiler compiler = mock(GoalCompiler.class);
        when(compiler.compileWithCorrection(any(ChatModel.class), anyString(), any(TaskPlan.class),
                anyString(), anyList(), any(TaskGraph.class))).thenReturn(clarification);
        PlanValidator validator = mock(PlanValidator.class);
        when(validator.validate(any(TaskGraph.class), any(TaskPlan.class), any(Goal.class),
                any(TaskIntent.class))).thenReturn(new PlanValidationReport(List.of(), List.of()));
        RecoveryEngine recovery = mock(RecoveryEngine.class);
        when(recovery.classCount(any(StateSnapshot.class), eq(FailureClass.REWRITE_GRAPH))).thenReturn(2);
        when(recovery.classLimit(FailureClass.REWRITE_GRAPH)).thenReturn(2);
        SessionLock lock = mock(SessionLock.class);
        when(lock.renewSessionLock(sessionId, "fence")).thenReturn(true);
        PlannerProperties properties = new PlannerProperties();
        properties.setMaxRecoveries(3);
        PlanningLoop loop = new PlanningLoop(properties, compiler, validator, stateStore,
                new GraphScheduler(properties), mock(ToolRouter.class), mock(StepEvaluator.class),
                recovery, mock(TodoStateProjector.class), mock(NodeExecutor.class),
                new PlannerMetrics(), lock, mock(ToolSuccessStats.class));

        PlanningLoop.RunOutcome outcome = loop.runWithOutcome(mock(ChatModel.class), "", "继续", null,
                List.of(), TaskPlan.of(originalGoal.objective()), sessionId, "fence", executionId,
                null, PlanningRoute.resume("state", "RESUME_CURRENT_GRAPH"));

        assertThat(outcome.outcome()).isEqualTo(PlanningLoop.Outcome.BLOCKED_ON_HUMAN);
        assertThat(outcome.reason()).isEqualTo("INTENT_NEEDS_CLARIFICATION");
        verify(compiler, times(1)).compileWithCorrection(any(ChatModel.class), anyString(),
                any(TaskPlan.class), anyString(), anyList(), any(TaskGraph.class));
        StateSnapshot resumed = stateStore.get(sessionId).orElseThrow();
        assertThat(resumed.recoveryCount()).isEqualTo(4);
        assertThat(resumed.graph()).isEqualTo(clarification.graph());
        assertThat(resumed.execution().get(PlanningLoop.RECOVERY_CLARIFICATION).toString())
                .contains("done", "SUCCESS");
        assertThat(stateStore.events(sessionId))
                .anySatisfy(event -> {
                    assertThat(event.type()).isEqualTo(DomainEventType.RECOVERY_APPLIED);
                    assertThat(event.payload()).containsEntry("explicitUserResume", true)
                            .containsEntry("kind", FailureKind.DEPENDENCY_FAILED.name());
                });
        assertThat(outcome.reason()).isNotEqualTo("DEPENDENCY_FAILED");
    }

    private static TaskGraph blockedGraph() {
        TaskNode completed = new TaskNode("done", "已完成页面文件", "file_write",
                List.of(), List.of(), List.of("index.html"), TaskNodeStatus.SUCCESS, 90,
                DoneWhen.file("index.html"), "", "", 0, "index.html 已验收");
        TaskNode cancelled = new TaskNode("cancelled", "页面结构验收", "code",
                List.of("done"), List.of("index.html"), List.of("structure.test.js"),
                TaskNodeStatus.CANCELLED, 80, DoneWhen.file("structure.test.js"),
                "", "recovery_exhausted", 3, "");
        TaskNode blocked = new TaskNode("blocked", "运行页面测试", "shell",
                List.of("cancelled"), List.of("structure.test.js"), List.of("node --test structure.test.js"),
                TaskNodeStatus.PENDING, 70, DoneWhen.command("node --test structure.test.js"),
                "", "", 0, "");
        return new TaskGraph(List.of(completed, cancelled, blocked));
    }
}
