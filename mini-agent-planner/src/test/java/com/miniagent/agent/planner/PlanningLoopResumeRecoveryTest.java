package com.miniagent.agent.planner;

import com.miniagent.agent.core.NodeExecutor;
import com.miniagent.agent.task.TaskPlan;
import com.miniagent.agent.tool.CapabilityRegistry;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

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
                FailureKind.GENERIC, true)).isFalse();
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
                null, PlanningRoute.dag("state", "RESUME_CURRENT_GRAPH"));

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
