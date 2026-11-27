package com.miniagent.agent.planner;

import com.miniagent.agent.core.NodeExecutor;
import com.miniagent.agent.task.TaskPlan;
import com.miniagent.agent.tool.CapabilityRegistry;
import com.miniagent.agent.tool.ToolStatus;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real PlannerStateStore CAS transitions, including multiple user rounds and background wakes. */
class RecoveryBudgetContractTest {
    private static final String SESSION = "s_recovery_budget_contract";

    @Test void newUserRoundsResetRoundAndClassButDoNotDoubleAddAuditOrGeneration() {
        PlannerStateStore store = store();
        PlannerProperties properties = properties(1, 1);
        RecoveryEngine engine = engine(store, properties);
        StateSnapshot initial = store.get(SESSION).orElseThrow();
        StateSnapshot recovered;
        try (var round = RecoveryBudget.bindRound("user-one", true)) {
            StateSnapshot first = normalize(store, initial);
            recovered = engine.recover(SESSION, first, diagnosis()).orElseThrow();
            assertThat(recovered.execution()).containsEntry(PlannerStateStore.EXECUTION_ROUND_RECOVERY_COUNT, 1)
                    .containsEntry("recovery.LOCAL_REPAIR", 1)
                    .containsEntry(PlannerStateStore.EXECUTION_RECOVERY_COUNT_TOTAL, 1);
            recovered = markFailed(store, recovered);
            assertThat(engine.recover(SESSION, recovered, diagnosis())).isEmpty();
            // normalize 本身会推进 store 版本（commit）。不把返回值接回去，
            // 后面的 CAS 仍拿旧 version 提交，就会撞 VersionConflict —— 这是测试的状态管理问题，
            // 不是实现多推进了一次版本。
            StateSnapshot normalized = normalize(store, recovered);
            assertThat(normalized.execution()).isEqualTo(recovered.execution());
            recovered = normalized;
        }
        try (var wake = RecoveryBudget.bindRound("user-one", false)) {
            assertThat(RecoveryBudget.roundUsed(recovered)).isEqualTo(1);
            assertThat(engine.recover(SESSION, recovered, diagnosis())).isEmpty();
        }
        try (var round = RecoveryBudget.bindRound("user-two", true)) {
            StateSnapshot next = normalize(store, recovered);
            assertThat(next.recoveryCount()).isEqualTo(1);
            assertThat(RecoveryBudget.roundUsed(next)).isZero();
            assertThat(RecoveryBudget.classUsed(next, FailureClass.LOCAL_REPAIR)).isZero();
            assertThat(next.execution()).containsEntry(PlannerStateStore.EXECUTION_RECOVERY_COUNT_TOTAL, 1);
            StateSnapshot second = engine.recover(SESSION, next, diagnosis()).orElseThrow();
            assertThat(second.recoveryCount()).isEqualTo(2);
            assertThat(second.execution()).containsEntry(PlannerStateStore.EXECUTION_RECOVERY_COUNT_TOTAL, 2)
                    .containsEntry(PlannerStateStore.EXECUTION_ROUND_RECOVERY_COUNT, 1);
        }
    }

    @Test void oldSnapshotCannotGainAutomaticAllowanceAndUserMigrationPreservesAudit() {
        PlannerStateStore store = store();
        StateSnapshot initial = store.get(SESSION).orElseThrow();
        StateSnapshot legacy = new StateSnapshot(initial.version(), initial.sessionId(), initial.executionId(),
                initial.goal(), initial.graph(), Map.of("recovery.LOCAL_REPAIR", 3,
                PlannerStateStore.EXECUTION_RECOVERY_COUNT_TOTAL, 9), Map.of(), List.of(), 4);
        legacy = store.commit(SESSION, initial.version(), legacy);
        assertThat(RecoveryBudget.roundUsed(legacy)).isEqualTo(4);
        assertThat(engine(store, properties(3, 3)).recover(SESSION, legacy, diagnosis())).isEmpty();
        try (var round = RecoveryBudget.bindRound("explicit-user", true)) {
            StateSnapshot migrated = normalize(store, legacy);
            assertThat(RecoveryBudget.roundUsed(migrated)).isZero();
            assertThat(RecoveryBudget.classUsed(migrated, FailureClass.LOCAL_REPAIR)).isZero();
            assertThat(migrated.execution()).containsEntry(RecoveryBudget.LEGACY_TOTAL, 9)
                    .containsEntry(PlannerStateStore.EXECUTION_RECOVERY_COUNT_TOTAL, 9);
            StateSnapshot repaired = engine(store, properties(3, 3)).recover(SESSION, migrated, diagnosis()).orElseThrow();
            assertThat(repaired.execution()).containsEntry(PlannerStateStore.EXECUTION_RECOVERY_COUNT_TOTAL, 10);
            assertThat(repaired.recoveryCount()).isEqualTo(5);
        }
    }

    @Test void concurrentRecoveryConsumesExactlyOneSlotAndRejectsStaleDiagnosis() throws Exception {
        PlannerStateStore store = store();
        StateSnapshot expected;
        try (var round = RecoveryBudget.bindRound("user-concurrent", true)) {
            expected = normalize(store, store.get(SESSION).orElseThrow());
        }
        RecoveryEngine engine = engine(store, properties(3, 3));
        CountDownLatch start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var one = workers.submit(() -> { start.await(); return engine.recover(SESSION, expected, diagnosis()); });
            var two = workers.submit(() -> { start.await(); return engine.recover(SESSION, expected, diagnosis()); });
            start.countDown();
            assertThat(List.of(one.get(2, TimeUnit.SECONDS), two.get(2, TimeUnit.SECONDS)))
                    .filteredOn(Optional::isPresent).hasSize(1);
        }
        StateSnapshot current = store.get(SESSION).orElseThrow();
        assertThat(current.execution()).containsEntry(PlannerStateStore.EXECUTION_RECOVERY_COUNT_TOTAL, 1)
                .containsEntry(PlannerStateStore.EXECUTION_ROUND_RECOVERY_COUNT, 1);
        assertThat(engine.recover(SESSION, expected, diagnosis())).isEmpty();
        assertThat(store.events(SESSION)).filteredOn(event -> event.type() == DomainEventType.RECOVERY_APPLIED).hasSize(1);
    }

    @Test void unknownOrRunningNodeCannotConsumeRecoveryEvenInNewUserRound() {
        for (TaskNodeStatus status : List.of(TaskNodeStatus.OUTCOME_UNKNOWN, TaskNodeStatus.RUNNING)) {
            PlannerStateStore store = store();
            StateSnapshot state = store.get(SESSION).orElseThrow();
            TaskNode node = state.graph().byId("work").withStatus(status).withToolStatus(ToolStatus.UNKNOWN);
            state = store.commit(SESSION, state.version(), state.withGraph(state.graph().replace(node)));
            try (var round = RecoveryBudget.bindRound("user-safe", true)) {
                assertThat(engine(store, properties(3, 3)).recover(SESSION, state, diagnosis())).isEmpty();
            }
            assertThat(store.get(SESSION).orElseThrow().execution()).isEmpty();
        }
    }

    @Test void backgroundWakeCannotRotateRoundAndScopeRestoresItsPreviousBinding() {
        StateSnapshot state = store().get(SESSION).orElseThrow();
        try (var user = RecoveryBudget.bindRound("user-original", true)) {
            state = state.withExecution(RecoveryBudget.prepare(state));
            StateSnapshot prepared = state;
            try (var wake = RecoveryBudget.bindRound("unexpected-new-id", false)) {
                assertThatThrownBy(() -> RecoveryBudget.prepare(prepared))
                        .isInstanceOf(IllegalStateException.class).hasMessageContaining("cannot change recovery round");
            }
            assertThat(RecoveryBudget.prepare(state)).containsEntry(RecoveryBudget.ROUND_ID, "user-original");
        }
        assertThat(RecoveryBudget.prepare(state)).containsEntry(RecoveryBudget.ROUND_ID, "user-original");
    }

    @Test void llmReplanUsesTheSameRoundBudgetAndIgnoresHistoricalGeneration() {
        PlannerStateStore store = store();
        StateSnapshot base = store.get(SESSION).orElseThrow();
        for (int i = 0; i < 5; i++) base = store.commit(SESSION, base.version(), base.withRecoveryInc());
        PlannerProperties properties = properties(1, 1);
        GoalCompiler compiler = mock(GoalCompiler.class);
        TaskGraph replacement = new TaskGraph(List.of(work().withStatus(TaskNodeStatus.PENDING)));
        when(compiler.compileWithCorrection(any(), anyString(), any(), anyString(), anyList(), any()))
                .thenReturn(new GoalCompiler.CompileResult(base.goal(), replacement, false,
                        GoalCompiler.CompilationSource.MODEL, "", TaskIntent.fromGoal(base.goal())));
        PlanValidator validator = mock(PlanValidator.class);
        when(validator.validate(any(), any(), any(), any())).thenReturn(new PlanValidationReport(List.of(), List.of()));
        PlanningLoop loop = loop(store, properties, compiler, validator);
        FailureDiagnosis dx = new FailureDiagnosis(FailureClass.REWRITE_GRAPH, FailureKind.EVAL_FAILED,
                "work", "", "missing evidence", "read missing evidence");
        ChatModel recoveryModel = mock(ChatModel.class);
        when(recoveryModel.chat(any(ChatRequest.class))).thenAnswer(invocation -> {
            ChatRequest request = invocation.getArgument(0);
            var data = PlannerStateJson.MAPPER.readTree(
                    ((dev.langchain4j.data.message.UserMessage) request.messages().get(1)).singleText());
            Map<String, Object> decision = PlannerStateJson.MAPPER.convertValue(data.path("identity"), Map.class);
            decision.put("schemaVersion", 1);
            decision.put("action", "REPLAN");
            decision.put("rootCause", "The submitted evidence omits the required source facts.");
            decision.put("nextSteps", List.of("Read the declared source and submit the missing facts."));
            decision.put("evidenceNeeded", List.of("Source read receipt"));
            return ChatResponse.builder().aiMessage(AiMessage.from(
                    PlannerStateJson.MAPPER.writeValueAsString(decision))).build();
        });
        try (var round = RecoveryBudget.bindRound("user-replan", true)) {
            base = normalize(store, base);
            Object attempt = ReflectionTestUtils.invokeMethod(loop, "tryLlmReplan", SESSION, "fence", base,
                    base.graph().byId("work").withStatus(TaskNodeStatus.RUNNING), dx, recoveryModel, "continue",
                    TaskPlan.of("complete report"), List.of(), false);
            assertThat(attempt).isNotNull();
            StateSnapshot replanned = store.get(SESSION).orElseThrow();
            assertThat(replanned.recoveryCount()).isEqualTo(6);
            assertThat(replanned.execution()).containsEntry(PlannerStateStore.EXECUTION_ROUND_RECOVERY_COUNT, 1)
                    .containsEntry("recovery.REWRITE_GRAPH", 1)
                    .containsEntry(PlannerStateStore.EXECUTION_RECOVERY_COUNT_TOTAL, 6);
            assertThat(RecoveryBudget.available(replanned, FailureClass.LOCAL_REPAIR, 1, 3, false)).isFalse();
            verify(compiler, times(1)).compileWithCorrection(any(), anyString(), any(), anyString(), anyList(), any());
        }
    }

    @Test void exhaustionRetainsSuccessfulNodesAndOnlyANewUserRoundCanReleaseTheWait() {
        PlannerStateStore store = store();
        PlannerProperties properties = properties(1, 1);
        RecoveryEngine recovery = engine(store, properties);
        PlanningLoop loop = loop(store, properties, mock(GoalCompiler.class), mock(PlanValidator.class));
        StateSnapshot failed;
        try (var round = RecoveryBudget.bindRound("exhausted-round", true)) {
            StateSnapshot state = normalize(store, store.get(SESSION).orElseThrow());
            failed = recovery.recover(SESSION, state, diagnosis()).orElseThrow();
            failed = markFailed(store, failed);
            ReflectionTestUtils.invokeMethod(loop, "applyRecoveryOrCancel", SESSION, "fence", failed,
                    failed.graph().byId("work"), diagnosis(), "work", mock(ChatModel.class), "continue",
                    TaskPlan.of("complete report"), List.of());
            failed = store.get(SESSION).orElseThrow();
            assertThat(failed.graph().byId("work").status()).isEqualTo(TaskNodeStatus.AWAITING_CONFIRM);
            assertThat(failed.graph().byId("work").lastError()).contains(RecoveryBudget.EXHAUSTED_REASON);
            assertThat(failed.graph().byId("done").status()).isEqualTo(TaskNodeStatus.SUCCESS);
            assertThat(failed.graph().nodes()).noneMatch(node -> node.status() == TaskNodeStatus.CANCELLED);
            StateSnapshot sameRound = ReflectionTestUtils.invokeMethod(loop, "consumeHumanWait", SESSION, failed);
            assertThat(sameRound).isSameAs(failed);
        }
        try (var wake = RecoveryBudget.bindRound("exhausted-round", false)) {
            StateSnapshot unchanged = ReflectionTestUtils.invokeMethod(loop, "consumeHumanWait", SESSION, failed);
            assertThat(unchanged).isSameAs(failed);
        }
        try (var round = RecoveryBudget.bindRound("user-continued", true)) {
            // Normalize before consuming the wait too: both orders must preserve its original receipt.
            StateSnapshot normalized = normalize(store, failed);
            StateSnapshot resumed = ReflectionTestUtils.invokeMethod(loop, "consumeHumanWait", SESSION, normalized);
            assertThat(resumed.graph().byId("work").status()).isEqualTo(TaskNodeStatus.PENDING);
            assertThat(resumed.graph().byId("done").status()).isEqualTo(TaskNodeStatus.SUCCESS);
            assertThat(resumed.execution()).doesNotContainKeys("recoveryBudgetBlocked", PlanningLoop.EXECUTION_HUMAN_WAIT);
            assertThat(RecoveryBudget.roundUsed(resumed)).isZero();
            StateSnapshot next = markFailed(store, resumed);
            assertThat(recovery.recover(SESSION, next, diagnosis())).isPresent();
        }
    }

    private static PlannerStateStore store() {
        PlannerStateStore store = new PlannerStateStore();
        Goal goal = new Goal("goal", "complete report", "", "research", Map.of(), List.of(), List.of());
        TaskNode done = new TaskNode("done", "accepted receipt", "research", List.of(), List.of(), List.of(),
                TaskNodeStatus.SUCCESS, 90, DoneWhen.note(), "", "", 0, "accepted");
        store.init(SESSION, "execution-contract", goal, new TaskGraph(List.of(done, work())));
        return store;
    }
    private static TaskNode work() {
        return new TaskNode("work", "complete remaining work", "research", List.of("done"), List.of(), List.of(),
                TaskNodeStatus.FAILED, 80, DoneWhen.note(), "", "missing evidence", 0, "");
    }
    private static PlannerProperties properties(int round, int local) {
        PlannerProperties properties = new PlannerProperties();
        properties.setMaxRecoveries(round); properties.setMaxLocalRepair(local); properties.setMaxRewriteGraph(local);
        return properties;
    }
    private static RecoveryEngine engine(PlannerStateStore store, PlannerProperties properties) {
        return new RecoveryEngine(store, mock(CapabilityRegistry.class), properties);
    }
    private static FailureDiagnosis diagnosis() {
        return new FailureDiagnosis(FailureClass.LOCAL_REPAIR, FailureKind.EVAL_FAILED, "work", "read_file",
                "missing evidence", "read missing evidence");
    }
    private static StateSnapshot normalize(PlannerStateStore store, StateSnapshot state) {
        return store.commit(SESSION, state.version(), state.withExecution(RecoveryBudget.prepare(state)));
    }
    private static StateSnapshot markFailed(PlannerStateStore store, StateSnapshot state) {
        return store.commit(SESSION, state.version(), state.withGraph(state.graph().replace(
                state.graph().byId("work").withStatus(TaskNodeStatus.FAILED).withError("missing evidence"))));
    }
    private static PlanningLoop loop(PlannerStateStore store, PlannerProperties properties,
                                     GoalCompiler compiler, PlanValidator validator) {
        SessionLock lock = mock(SessionLock.class);
        when(lock.renewSessionLock(SESSION, "fence")).thenReturn(true);
        return new PlanningLoop(properties, compiler, validator, store, new GraphScheduler(properties),
                mock(ToolRouter.class), mock(StepEvaluator.class), engine(store, properties),
                mock(TodoStateProjector.class), mock(NodeExecutor.class), new PlannerMetrics(), lock, mock(ToolSuccessStats.class));
    }
}
