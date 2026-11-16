package com.miniagent.agent.planner;

import com.miniagent.agent.core.AgentLoop;
import com.miniagent.agent.core.NodeExecutor;
import com.miniagent.agent.permission.PermissionContext;
import com.miniagent.agent.task.TaskPlan;
import com.miniagent.agent.todo.LlmJudgeTodoValidator;
import com.miniagent.agent.tool.CapabilityRegistry;
import com.miniagent.agent.tool.ToolErrorCode;
import com.miniagent.agent.tool.ToolResult;
import com.miniagent.agent.tool.WorkspaceContext;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PlanningLoopAcceptanceReceiptTest {
    @TempDir Path workspace;

    @BeforeEach void bindWorkspace() throws Exception {
        WorkspaceContext.setRootOverride(workspace);
        Files.writeString(workspace.resolve("report.md"), "Quarterly sales: January 10, February 20, March 30. Refunds are excluded from every month.");
    }
    @AfterEach void clear() { WorkspaceContext.clearRootOverride(); PermissionContext.clear(); }

    @Test void nodeAndReceiptCommitTogetherAndFinalAcceptanceDoesNotCallTheJudgeAgain() {
        Fixture f = fixture(new PlannerStateStore());
        doAnswer(call -> {
            StateSnapshot stored = f.store.get("chat").orElseThrow();
            assertEquals(TaskNodeStatus.SUCCESS, stored.graph().byId("file").status());
            assertTrue(stored.execution().get(SemanticAcceptanceReceipts.EXECUTION_KEY) instanceof Map);
            assertTrue(f.store.events("chat").stream().anyMatch(e -> e.type() == DomainEventType.NODE_SUCCESS));
            return null;
        }).when(f.feedback).commit(anyString(), anyLong(), anyString(), any(), any(), any());
        assertTrue(run(f).goalSatisfied());
        verify(f.judge, times(2)).validate(any(), anyString());
        verify(f.feedback, times(1)).commit(anyString(), anyLong(), anyString(), any(),
                eq(NodeTerminalCommitPort.Result.SUCCESS), any());
    }

    @Test void aFailedTerminalCasCannotPersistItsInMemorySemanticVerdict() {
        PlannerStateStore store = new PlannerStateStore() {
            @Override public StateSnapshot commit(String session, long version, StateSnapshot next, List<DomainEvent> events) {
                if (next.graph().byId("file").status() == TaskNodeStatus.SUCCESS) {
                    assertTrue(next.execution().containsKey(SemanticAcceptanceReceipts.EXECUTION_KEY));
                    throw new VersionConflictException(version, version + 1);
                }
                return super.commit(session, version, next, events);
            }
        };
        Fixture f = fixture(store);
        assertFalse(run(f).goalSatisfied());
        StateSnapshot stored = store.get("chat").orElseThrow();
        assertFalse(stored.execution().containsKey(SemanticAcceptanceReceipts.EXECUTION_KEY));
        assertNotEquals(TaskNodeStatus.SUCCESS, stored.graph().byId("file").status());
        assertTrue(store.events("chat").stream().noneMatch(e -> e.type() == DomainEventType.NODE_SUCCESS));
        verifyNoInteractions(f.feedback);
    }

    @Test void fileChangingAfterNodeCommitIsReadAndJudgedAgainAtGraphAcceptance() {
        PlannerStateStore store = new PlannerStateStore() {
            boolean changed;
            @Override public StateSnapshot commit(String session, long version, StateSnapshot next, List<DomainEvent> events) {
                StateSnapshot committed = super.commit(session, version, next, events);
                if (!changed && next.graph().byId("file").status() == TaskNodeStatus.SUCCESS) {
                    changed = true;
                    try { Files.writeString(workspace.resolve("report.md"), "CHANGED: Refunds have been included and the actual quarterly results do not meet the original requirements."); }
                    catch (Exception failure) { throw new AssertionError(failure); }
                }
                return committed;
            }
        };
        Fixture f = fixture(store);
        when(f.judge.validate(any(), anyString())).thenAnswer(call ->
                call.<String>getArgument(1).contains("CHANGED") ? "modified content is not accepted" : null);
        assertFalse(run(f).goalSatisfied());
        verify(f.judge, times(3)).validate(any(), anyString());
        assertEquals(0, f.metrics.snapshot().get("graphsCompleted"));
        assertFalse(store.get("chat").orElseThrow().execution().containsKey(SemanticAcceptanceReceipts.EXECUTION_KEY));
    }

    private Fixture fixture(PlannerStateStore store) {
        PlannerProperties properties = new PlannerProperties();
        properties.setMaxOuterRounds(5); properties.setMaxRecoveries(0);
        Goal goal = new Goal("goal", "Write report", "", Map.of(), List.of(), List.of());
        TaskNode file = new TaskNode("file", "Write report", "file_write", List.of(), List.of(), List.of("report"),
                TaskNodeStatus.PENDING, 0, DoneWhen.file("report.md").withChecks(List.of("three months", "exclude refunds")), "", "", 0, "")
                .withProcedure(42L, 1);
        store.init("chat", "execution", goal, new TaskGraph(List.of(file)));
        NodeExecutor executor = mock(NodeExecutor.class);
        ToolResult result = ToolResult.success("written report.md");
        when(executor.execute(any(), anyString(), anyString(), any(), anyList(), anyInt(), any(), any()))
                .thenReturn(new AgentLoop.LoopOutcome(result.legacyText(), "SUCCESS",
                        List.of(ToolExecutionResultMessage.from("write", "write_file", result.legacyText())),
                        List.of("write_file"), ToolErrorCode.NONE, result));
        CapabilityRegistry capabilities = mock(CapabilityRegistry.class);
        when(capabilities.toolsFor(anyString())).thenReturn(List.of("write_file", "read_file"));
        SessionLock lease = mock(SessionLock.class);
        when(lease.renewSessionLock(anyString(), anyString())).thenReturn(true);
        PlannerMetrics metrics = new PlannerMetrics();
        LlmJudgeTodoValidator judge = mock(LlmJudgeTodoValidator.class);
        when(judge.acceptanceIdentity()).thenReturn("fixture-judge");
        NodeTerminalCommitPort feedback = mock(NodeTerminalCommitPort.class);
        PlanningLoop loop = new PlanningLoop(properties, mock(GoalCompiler.class), new PlanValidator(), store,
                new GraphScheduler(properties), new ToolRouter(capabilities), new StepEvaluator(properties, metrics, judge),
                new RecoveryEngine(store, capabilities, properties), mock(TodoStateProjector.class), executor,
                metrics, lease, new ToolSuccessStats());
        ReflectionTestUtils.setField(loop, "nodeTerminalCommitPort", feedback);
        return new Fixture(loop, store, judge, feedback, metrics);
    }

    private static PlanningLoop.RunOutcome run(Fixture f) {
        return f.loop.runWithOutcome(null, "system", "继续", null, List.of(), TaskPlan.of("Write report"),
                "chat", "token", "unused-resume-id", null, PlanningRoute.dag("state", "RESUME_CURRENT_GRAPH"));
    }
    private record Fixture(PlanningLoop loop, PlannerStateStore store, LlmJudgeTodoValidator judge,
                           NodeTerminalCommitPort feedback, PlannerMetrics metrics) { }
}
