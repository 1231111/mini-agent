package com.miniagent.agent.planner;

import com.miniagent.agent.core.AgentLoop;
import com.miniagent.agent.core.AgentStreamSink;
import com.miniagent.agent.core.NodeExecutor;
import com.miniagent.agent.permission.PermissionContext;
import com.miniagent.agent.task.TaskPlan;
import com.miniagent.agent.tool.CapabilityRegistry;
import com.miniagent.agent.tool.ToolErrorCode;
import com.miniagent.agent.tool.ToolResult;
import com.miniagent.agent.tool.ToolStatus;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlanningLoopReplyBoundaryTest {
    private static final String DRAFT = "UNACCEPTED_NODE_DRAFT_MUST_NOT_REACH_USER";

    @AfterEach void clear() { PermissionContext.clear(); }

    @Test void nodeDraftAndResetNeverReachUserBeforeItsVerdictOrGraphAcceptance() {
        AgentStreamSink user = mock(AgentStreamSink.class);
        AtomicBoolean terminalWriteObserved = new AtomicBoolean();
        PlannerStateStore store = new PlannerStateStore() {
            @Override public StateSnapshot commit(String session, long version, StateSnapshot next, List<DomainEvent> events) {
                if (events.stream().anyMatch(event -> event.type() == DomainEventType.NODE_SUCCESS)) {
                    terminalWriteObserved.set(true);
                    verify(user, never()).onAnswerToken(anyString());
                    verify(user, never()).onAnswerReset();
                }
                return super.commit(session, version, next, events);
            }
        };
        Fixture f = fixture(store, graph(node("lookup", List.of())));
        when(f.executor.execute(any(), anyString(), anyString(), any(), anyList(), anyInt(), any(), any()))
                .thenAnswer(call -> {
                    AgentStreamSink node = call.getArgument(7);
                    assertNotSame(user, node);
                    node.onThinking("Inspecting a source");
                    node.onSubGoal("Look up facts", 0, 1);
                    node.onContext(100, 8000);
                    node.onAnswerToken(DRAFT);
                    node.onAnswerReset();
                    return success("Verified fact and source: https://example.test/news", "Verified fact and source: https://example.test/news");
                });

        PlanningLoop.RunOutcome result = run(f, user);

        assertTrue(terminalWriteObserved.get());
        assertTrue(result.goalSatisfied());
        assertEquals("Verified fact and source: https://example.test/news", result.answer(),
                "Only the exact, durably accepted terminal output is deliverable");
        verify(user).onThinking("Inspecting a source");
        verify(user).onSubGoal("Look up facts", 0, 1);
        verify(user).onContext(100, 8000);
        verify(user, never()).onAnswerToken(anyString());
        verify(user, never()).onAnswerReset();
    }

    @Test void failedNodeProseCannotBecomeAnUnfinishedAnswerOrAStreamedDraft() {
        Fixture f = fixture(new PlannerStateStore(), graph(node("lookup", List.of())));
        AgentStreamSink user = mock(AgentStreamSink.class);
        when(f.executor.execute(any(), anyString(), anyString(), any(), anyList(), anyInt(), any(), any()))
                .thenAnswer(call -> {
                    call.<AgentStreamSink>getArgument(7).onAnswerToken(DRAFT);
                    return new AgentLoop.LoopOutcome(DRAFT, "FAILURE", List.of(), List.of(), ToolErrorCode.EXECUTION_FAILED);
                });

        PlanningLoop.RunOutcome result = run(f, user);

        assertFalse(result.goalSatisfied());
        assertFalse(result.answer().contains(DRAFT));
        assertTrue(result.answer().contains("任务尚未完成"));
        verify(user, never()).onAnswerToken(anyString());
    }

    @Test void aRejectedSuccessCasCannotPublishItsCandidate() {
        PlannerStateStore store = new PlannerStateStore() {
            @Override public StateSnapshot commit(String session, long version, StateSnapshot next, List<DomainEvent> events) {
                if (events.stream().anyMatch(event -> event.type() == DomainEventType.NODE_SUCCESS))
                    throw new VersionConflictException(version, version + 1);
                return super.commit(session, version, next, events);
            }
        };
        Fixture f = fixture(store, graph(node("lookup", List.of())));
        when(f.executor.execute(any(), anyString(), anyString(), any(), anyList(), anyInt(), any(), any()))
                .thenReturn(success(DRAFT, DRAFT));

        PlanningLoop.RunOutcome result = run(f, null);

        assertFalse(result.goalSatisfied());
        assertFalse(result.answer().contains(DRAFT));
        assertTrue(store.events("chat").stream().noneMatch(event -> event.type() == DomainEventType.NODE_SUCCESS));
    }

    @Test void acceptingRealToolEvidenceDoesNotAlsoCertifyUnrelatedModelCommentary() {
        Fixture f = fixture(new PlannerStateStore(), graph(node("lookup", List.of())));
        when(f.executor.execute(any(), anyString(), anyString(), any(), anyList(), anyInt(), any(), any()))
                .thenReturn(success(DRAFT, "Verified result: https://example.test/actual-source"));

        PlanningLoop.RunOutcome result = run(f, null);

        assertTrue(result.goalSatisfied());
        assertFalse(result.answer().contains(DRAFT));
        assertTrue(result.answer().contains("https://example.test/actual-source"));
    }

    @Test void graphRejectionClearsThePreviouslyCommittedCandidate() {
        Fixture f = fixture(new PlannerStateStore(), graph(node("lookup", List.of())));
        when(f.executor.execute(any(), anyString(), anyString(), any(), anyList(), anyInt(), any(), any()))
                .thenReturn(success(DRAFT, DRAFT));
        doReturn(StepEvaluator.GraphEval.fail("lookup", "graph requirement was not satisfied"))
                .when(f.evaluator).evaluateGraph(any(), any(), any());

        PlanningLoop.RunOutcome result = run(f, null);

        assertFalse(result.goalSatisfied());
        assertFalse(result.answer().contains(DRAFT));
    }

    @Test void anExecutionReplacedWhileTheModelRunsCannotLeakItsLateDraft() {
        Fixture f = fixture(new PlannerStateStore(), graph(node("lookup", List.of())));
        AgentStreamSink user = mock(AgentStreamSink.class);
        when(f.executor.execute(any(), anyString(), anyString(), any(), anyList(), anyInt(), any(), any()))
                .thenAnswer(call -> {
                    call.<AgentStreamSink>getArgument(7).onAnswerToken(DRAFT);
                    StateSnapshot old = f.store.get("chat").orElseThrow();
                    f.store.init("chat", "replacement-execution", old.goal(), graph(node("lookup", List.of())));
                    return success(DRAFT, DRAFT);
                });

        PlanningLoop.RunOutcome result = run(f, user);

        assertEquals("STALE_EXECUTION", result.reason());
        assertFalse(result.answer().contains(DRAFT));
        verify(user, never()).onAnswerToken(anyString());
    }

    @Test void planRevisionCannotReuseAPreviousTerminalCandidate() {
        PlannerStateStore store = new PlannerStateStore() {
            boolean revised;
            @Override public StateSnapshot commit(String session, long version, StateSnapshot next, List<DomainEvent> events) {
                StateSnapshot committed = super.commit(session, version, next, events);
                if (!revised && events.stream().anyMatch(event -> event.type() == DomainEventType.NODE_SUCCESS)) {
                    revised = true;
                    TaskNode updated = committed.graph().byId("lookup").withOutput(
                            "Revised plan's verified fact: https://example.test/revised", Map.of("result", "revised evidence"));
                    super.commit(session, committed.version(), committed.revisePlan(graph(updated), committed.goal(), "fixture revision"), List.of());
                }
                return committed;
            }
        };
        Fixture f = fixture(store, graph(node("lookup", List.of())));
        when(f.executor.execute(any(), anyString(), anyString(), any(), anyList(), anyInt(), any(), any()))
                .thenReturn(success(DRAFT, DRAFT));

        PlanningLoop.RunOutcome result = run(f, null);

        assertTrue(result.goalSatisfied());
        assertFalse(result.answer().contains(DRAFT));
        assertTrue(result.answer().contains("https://example.test/revised"));
    }

    @ParameterizedTest @ValueSource(strings = {"USER_QUESTION", "PERM_ASK"})
    void humanInteractionStillReturnsTheCurrentQuestion(String reason) {
        Fixture f = fixture(new PlannerStateStore(), graph(node("lookup", List.of())));
        AgentStreamSink user = mock(AgentStreamSink.class);
        when(f.executor.execute(any(), anyString(), anyString(), any(), anyList(), anyInt(), any(), any()))
                .thenAnswer(call -> {
                    call.<AgentStreamSink>getArgument(7).onAnswerToken("Please select a departure city");
                    return new AgentLoop.LoopOutcome("Please select a departure city", reason, List.of(),
                            List.of("ask_user_question"), ToolErrorCode.NONE);
                });

        PlanningLoop.RunOutcome result = run(f, user);

        assertEquals(PlanningLoop.Outcome.BLOCKED_ON_HUMAN, result.outcome());
        assertEquals(reason, result.reason());
        assertEquals("Please select a departure city", result.answer());
        assertTrue(f.store.get("chat").orElseThrow().graph().hasAwaitingConfirm());
        verify(user, never()).onAnswerToken(anyString());
    }

    @Test void multipleTerminalNodesDeliverAllAcceptedFactsInsteadOfTheLastNodesProse() {
        Fixture f = fixture(new PlannerStateStore(), graph(node("first", List.of()), node("second", List.of())));
        AtomicInteger count = new AtomicInteger();
        when(f.executor.execute(any(), anyString(), anyString(), any(), anyList(), anyInt(), any(), any()))
                .thenAnswer(call -> {
                    int index = count.incrementAndGet();
                    return success(DRAFT + index, "Verified fact " + index + ": https://example.test/source" + index);
                });

        PlanningLoop.RunOutcome result = run(f, null);

        assertTrue(result.goalSatisfied());
        assertFalse(result.answer().contains(DRAFT));
        assertTrue(result.answer().contains("https://example.test/source1"));
        assertTrue(result.answer().contains("https://example.test/source2"));
    }

    @Test void aTerminalStepMarkerCannotReviveAnIntermediateDraft() {
        Fixture f = fixture(new PlannerStateStore(), graph(node("lookup", List.of()), node("answer", List.of("lookup"))));
        when(f.executor.execute(any(), anyString(), anyString(), any(), anyList(), anyInt(), any(), any()))
                .thenReturn(success(DRAFT, "Verified source: https://example.test/source"),
                        success(AgentLoop.STEP_SEGMENT_DONE, "Verified final fact: https://example.test/final"));

        PlanningLoop.RunOutcome result = run(f, null);

        assertTrue(result.goalSatisfied());
        assertFalse(result.answer().contains(DRAFT));
        assertTrue(result.answer().contains("https://example.test/final"));
    }

    @Test void acceptedCandidateIsBoundToItsExactCommittedNodeAndExecution() {
        TaskNode success = node("lookup", List.of()).withStatus(TaskNodeStatus.SUCCESS)
                .withOutput("Final facts", Map.of("result", "Final facts"));
        StateSnapshot snapshot = new StateSnapshot(4, "chat", "execution", null, graph(success), Map.of(), Map.of(), List.of(), 0);
        PlanningLoop.AcceptedNodeAnswer answer = PlanningLoop.AcceptedNodeAnswer.fromCommitted(snapshot, success.id(), "Final facts");
        assertNotNull(answer);
        assertTrue(answer.matches(snapshot));
        assertFalse(answer.matches(new StateSnapshot(4, "other-chat", "execution", null, graph(success), Map.of(), Map.of(), List.of(), 0)));
        assertFalse(answer.matches(new StateSnapshot(4, "chat", "replacement", null, graph(success), Map.of(), Map.of(), List.of(), 0)));
        assertFalse(answer.matches(snapshot.revisePlan(snapshot.graph(), snapshot.goal(), "new version")));
        assertFalse(answer.matches(snapshot.withGraph(graph(success.withOutput("changed evidence", Map.of("result", "changed evidence"))))));
        assertNull(PlanningLoop.AcceptedNodeAnswer.fromCommitted(snapshot.withGraph(graph(success.withStatus(TaskNodeStatus.FAILED))), success.id(), DRAFT));
        assertNull(PlanningLoop.AcceptedNodeAnswer.fromCommitted(snapshot, success.id(), "Unverified model commentary"));
        TaskNode other = node("other", List.of()).withStatus(TaskNodeStatus.SUCCESS)
                .withOutput("Other accepted facts", Map.of("result", "Other accepted facts"));
        assertNull(PlanningLoop.AcceptedNodeAnswer.fromCommitted(snapshot.withGraph(graph(success, other)), success.id(), "Final facts"));
        TaskNode successor = node("successor", List.of(success.id()));
        assertNull(PlanningLoop.AcceptedNodeAnswer.fromCommitted(snapshot.withGraph(graph(success, successor)), success.id(), "Final facts"));
        assertNull(PlanningLoop.nodeEvidenceStream(null));
    }

    private static Fixture fixture(PlannerStateStore store, TaskGraph graph) {
        PlannerProperties properties = new PlannerProperties();
        properties.setMaxOuterRounds(8); properties.setMaxRecoveries(0);
        store.init("chat", "execution", new Goal("goal", "Verified facts", "", Map.of(), List.of(), List.of()), graph);
        CapabilityRegistry capabilities = mock(CapabilityRegistry.class);
        when(capabilities.toolsFor(anyString())).thenReturn(List.of("web_search"));
        NodeExecutor executor = mock(NodeExecutor.class);
        SessionLock lease = mock(SessionLock.class);
        when(lease.renewSessionLock(anyString(), anyString())).thenReturn(true);
        PlannerMetrics metrics = new PlannerMetrics();
        StepEvaluator evaluator = spy(new StepEvaluator(properties, metrics, null));
        PlanningLoop loop = new PlanningLoop(properties, mock(GoalCompiler.class), new PlanValidator(), store,
                new GraphScheduler(properties), new ToolRouter(capabilities), evaluator,
                new RecoveryEngine(store, capabilities, properties), mock(TodoStateProjector.class), executor,
                metrics, lease, new ToolSuccessStats());
        return new Fixture(loop, store, executor, evaluator);
    }

    private static AgentLoop.LoopOutcome success(String text, String evidence) {
        ToolResult receipt = ToolResult.success(evidence);
        return new AgentLoop.LoopOutcome(text, "SUCCESS",
                List.of(ToolExecutionResultMessage.from("source", "web_search", receipt.legacyText())),
                List.of("web_search"), ToolErrorCode.NONE, receipt);
    }

    private static TaskNode node(String id, List<String> dependencies) {
        return new TaskNode(id, id + " lookup", "web", dependencies, List.of(), List.of("result"),
                TaskNodeStatus.PENDING, 0, DoneWhen.note(), "", "", 0, "");
    }
    private static TaskGraph graph(TaskNode... nodes) { return new TaskGraph(List.of(nodes)); }
    private static PlanningLoop.RunOutcome run(Fixture fixture, AgentStreamSink userStream) {
        return fixture.loop.runWithOutcome(null, "system", "继续", null, List.of(), TaskPlan.of("Verified facts"),
                "chat", "fence", "unused-resume-id", userStream, PlanningRoute.dag("resume", "RESUME_CURRENT_GRAPH"));
    }
    private record Fixture(PlanningLoop loop, PlannerStateStore store, NodeExecutor executor, StepEvaluator evaluator) { }
}
