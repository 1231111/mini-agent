package com.miniagent.agent.planner;

import com.miniagent.agent.core.AgentLoop;
import com.miniagent.agent.core.NodeExecutor;
import com.miniagent.agent.permission.PermissionContext;
import com.miniagent.agent.task.TaskPlan;
import com.miniagent.agent.tool.CapabilityRegistry;
import com.miniagent.agent.tool.ToolErrorCode;
import com.miniagent.agent.tool.ToolResult;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlanningLoopDurableSettlementTest {
    @AfterEach void clear() { PermissionContext.clear(); }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void aFailedTerminalWriteCannotEmitAVerdictOrTeachMemory(boolean success) {
        PlannerStateStore store = new PlannerStateStore() {
            @Override public StateSnapshot commit(String session, long version, StateSnapshot next, List<DomainEvent> events) {
                TaskNode node = next.graph().byId("node");
                if (node.status() == TaskNodeStatus.SUCCESS || node.status() == TaskNodeStatus.FAILED)
                    throw new IllegalStateException("fixture terminal database failure");
                return super.commit(session, version, next, events);
            }
        };
        Fixture f = fixture(store);
        when(f.executor.execute(any(), anyString(), anyString(), any(), anyList(), anyInt(), any(), any()))
                .thenReturn(outcome(success ? ToolResult.success("confirmed fixture result")
                        : ToolResult.failure(ToolErrorCode.INVALID_ARGUMENT, "fixture rejected", false), "SUCCESS"));

        assertThrows(IllegalStateException.class, () -> run(f));

        assertEquals(TaskNodeStatus.RUNNING, store.get("chat").orElseThrow().graph().byId("node").status());
        assertTrue(store.events("chat").stream().noneMatch(event ->
                event.type() == DomainEventType.NODE_SUCCESS || event.type() == DomainEventType.NODE_FAILED));
        assertEquals(0, f.metrics.snapshot().get("nodesSuccess"));
        assertEquals(0, f.metrics.snapshot().get("nodesFailed"));
        assertEquals(0, f.stats.samples("web"));
        verifyNoInteractions(f.memory);
    }

    @Test void memoryObservesOnlyTheCommittedNodeAndItsAtomicAuditVerdict() {
        Fixture f = fixture(new PlannerStateStore());
        when(f.executor.execute(any(), anyString(), anyString(), any(), anyList(), anyInt(), any(), any()))
                .thenReturn(outcome(ToolResult.success("confirmed fixture result"), "SUCCESS"));
        doAnswer(call -> {
            TaskNode committed = f.store.get("chat").orElseThrow().graph().byId("node");
            assertEquals(TaskNodeStatus.SUCCESS, committed.status());
            assertEquals("confirmed fixture result", committed.output());
            assertEquals(committed, call.getArgument(3));
            assertEquals(1, f.store.events("chat").stream()
                    .filter(event -> event.type() == DomainEventType.NODE_SUCCESS).count());
            return null;
        }).when(f.memory).commit(anyString(), anyLong(), anyString(), any(), any(), any());

        assertTrue(run(f).goalSatisfied());
        verify(f.memory, times(1)).commit(anyString(), anyLong(), anyString(), any(),
                eq(NodeTerminalCommitPort.Result.SUCCESS), any());
        assertEquals(1, f.metrics.snapshot().get("nodesSuccess"));
        assertEquals(1, f.stats.samples("web"));
    }

    @Test void aCompareAndSetConflictCannotPublishSuccessOrTriggerLearning() {
        PlannerStateStore store = new PlannerStateStore() {
            @Override public StateSnapshot commit(String session, long version, StateSnapshot next, List<DomainEvent> events) {
                if (next.graph().byId("node").status() == TaskNodeStatus.SUCCESS)
                    throw new VersionConflictException(version, version + 1);
                return super.commit(session, version, next, events);
            }
        };
        Fixture f = fixture(store);
        when(f.executor.execute(any(), anyString(), anyString(), any(), anyList(), anyInt(), any(), any()))
                .thenReturn(outcome(ToolResult.success("confirmed fixture result"), "SUCCESS"));
        assertFalse(run(f).goalSatisfied());
        assertEquals(0, f.metrics.snapshot().get("nodesSuccess"));
        assertEquals(0, f.stats.samples("web"));
        assertTrue(store.events("chat").stream().noneMatch(event -> event.type() == DomainEventType.NODE_SUCCESS));
        verifyNoInteractions(f.memory);
    }

    @Test void losingTheLeaseDuringNodeEvaluationCannotTeachOrSettleTheVerdict() {
        Fixture f = fixture(new PlannerStateStore());
        when(f.executor.execute(any(), anyString(), anyString(), any(), anyList(), anyInt(), any(), any()))
                .thenReturn(outcome(ToolResult.success("confirmed fixture result"), "SUCCESS"));
        doAnswer(call -> {
            when(f.lock.renewSessionLock(anyString(), anyString())).thenReturn(false);
            return StepEvaluator.EvalResult.pass();
        }).when(f.evaluator).evaluate(any(), any(ToolResult.class), anyString());
        IllegalStateException stopped = assertThrows(IllegalStateException.class, () -> run(f));
        assertEquals("SESSION_LOCK_LOST", stopped.getMessage());
        assertEquals(TaskNodeStatus.RUNNING, f.store.get("chat").orElseThrow().graph().byId("node").status());
        assertTrue(f.store.events("chat").stream().noneMatch(event -> event.type() == DomainEventType.NODE_SUCCESS));
        assertEquals(0, f.metrics.snapshot().get("nodesSuccess"));
        verifyNoInteractions(f.memory);
    }

    @ParameterizedTest @ValueSource(strings = {"OUTCOME_UNKNOWN", "CANCELLED", "DEADLINE_EXCEEDED"})
    void anUnconfirmedEffectCannotBecomeAProcedureFailure(String reason) {
        Fixture f = fixture(new PlannerStateStore());
        when(f.executor.execute(any(), anyString(), anyString(), any(), anyList(), anyInt(), any(), any()))
                .thenReturn(outcome(ToolResult.unknown("effect may have completed", null), reason));
        assertFalse(run(f).goalSatisfied());
        assertEquals(TaskNodeStatus.OUTCOME_UNKNOWN, f.store.get("chat").orElseThrow().graph().byId("node").status());
        verifyNoInteractions(f.memory);
        assertEquals(0, f.stats.samples("web"));
    }

    @Test void aGraphReplacedDuringAcceptanceCannotBeReportedAsCompleted() {
        Fixture f = fixture(new PlannerStateStore());
        when(f.executor.execute(any(), anyString(), anyString(), any(), anyList(), anyInt(), any(), any()))
                .thenReturn(outcome(ToolResult.success("confirmed fixture result"), "SUCCESS"));
        doAnswer(call -> {
            StateSnapshot original = f.store.get("chat").orElseThrow();
            f.store.init("chat", "replacement-execution", original.goal(), graph());
            return StepEvaluator.GraphEval.pass();
        }).when(f.evaluator).evaluateGraph(any(), any());

        PlanningLoop.RunOutcome result = run(f);

        assertFalse(result.goalSatisfied());
        assertEquals("STALE_EXECUTION", result.reason());
        assertEquals("replacement-execution", f.store.get("chat").orElseThrow().executionId());
        assertEquals(TaskNodeStatus.PENDING, f.store.get("chat").orElseThrow().graph().byId("node").status());
        assertEquals(0, f.metrics.snapshot().get("graphsCompleted"));
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void lossOfTheLeaseDuringAcceptanceCannotProduceAVerdict(boolean accepted) {
        Fixture f = fixture(new PlannerStateStore());
        when(f.executor.execute(any(), anyString(), anyString(), any(), anyList(), anyInt(), any(), any()))
                .thenReturn(outcome(ToolResult.success("confirmed fixture result"), "SUCCESS"));
        doAnswer(call -> {
            when(f.lock.renewSessionLock(anyString(), anyString())).thenReturn(false);
            return accepted ? StepEvaluator.GraphEval.pass() : StepEvaluator.GraphEval.fail("node", "fixture rejection");
        }).when(f.evaluator).evaluateGraph(any(), any());
        PlanningLoop.RunOutcome result = run(f);
        assertFalse(result.goalSatisfied());
        assertEquals("SESSION_LOCK_LOST", result.reason());
        assertEquals(0, f.metrics.snapshot().get("graphsCompleted"));
        assertTrue(f.store.events("chat").stream().noneMatch(event -> event.type() == DomainEventType.NODE_FAILED));
        verify(f.memory, never()).commit(anyString(), anyLong(), anyString(), any(),
                eq(NodeTerminalCommitPort.Result.FAILURE), any());
    }

    private static AgentLoop.LoopOutcome outcome(ToolResult receipt, String reason) {
        return new AgentLoop.LoopOutcome(receipt.legacyText(), reason,
                List.of(ToolExecutionResultMessage.from("receipt", "web_search", receipt.legacyText())),
                List.of("web_search"), ToolErrorCode.NONE, receipt);
    }

    private static PlanningLoop.RunOutcome run(Fixture f) {
        return f.loop.runWithOutcome(null, "system", "继续", null, List.of(), TaskPlan.of("fixture lookup"),
                "chat", "run-token", "unused-resume-id", null, PlanningRoute.dag("state", "RESUME_CURRENT_GRAPH"));
    }

    private static Fixture fixture(PlannerStateStore store) {
        PlannerProperties properties = new PlannerProperties();
        properties.setMaxOuterRounds(5); properties.setMaxRecoveries(0);
        Goal goal = new Goal("goal", "fixture lookup", "", Map.of(), List.of(), List.of());
        store.init("chat", "fixture-execution", goal, graph());
        CapabilityRegistry capabilities = mock(CapabilityRegistry.class);
        when(capabilities.toolsFor(anyString())).thenReturn(List.of("web_search"));
        SessionLock lock = mock(SessionLock.class);
        when(lock.renewSessionLock(anyString(), anyString())).thenReturn(true);
        PlannerMetrics metrics = new PlannerMetrics();
        ToolSuccessStats stats = new ToolSuccessStats();
        NodeExecutor executor = mock(NodeExecutor.class);
        StepEvaluator evaluator = spy(new StepEvaluator(properties, metrics, null));
        NodeTerminalCommitPort memory = mock(NodeTerminalCommitPort.class);
        PlanningLoop loop = new PlanningLoop(properties, mock(GoalCompiler.class), new PlanValidator(), store,
                new GraphScheduler(properties), new ToolRouter(capabilities), evaluator,
                new RecoveryEngine(store, capabilities, properties), mock(TodoStateProjector.class), executor,
                metrics, lock, stats);
        ReflectionTestUtils.setField(loop, "nodeTerminalCommitPort", memory);
        return new Fixture(loop, store, executor, evaluator, memory, metrics, stats, lock);
    }

    private static TaskGraph graph() {
        return new TaskGraph(List.of(new TaskNode("node", "fixture lookup", "web", List.of(), List.of(), List.of("result"),
                TaskNodeStatus.PENDING, 0, DoneWhen.note(), "", "", 0, "").withProcedure(42L, 1)));
    }

    private record Fixture(PlanningLoop loop, PlannerStateStore store, NodeExecutor executor, StepEvaluator evaluator,
                           NodeTerminalCommitPort memory, PlannerMetrics metrics, ToolSuccessStats stats, SessionLock lock) {}
}
