package com.miniagent.agent.planner;

import com.miniagent.agent.core.AgentLoop;
import com.miniagent.agent.core.NodeExecutor;
import com.miniagent.agent.permission.PermissionContext;
import com.miniagent.agent.task.TaskPlan;
import com.miniagent.agent.tool.CapabilityRegistry;
import com.miniagent.agent.tool.ToolErrorCode;
import com.miniagent.agent.tool.ToolResult;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Runs the scheduler/executor/recovery loop, rather than calling its compiler helper. */
class PlanningLoopRuntimeRecoveryContextTest {
    private static final String SESSION = "runtime-recovery";
    private static final String EXECUTION = "original-execution";
    private static final String OBJECTIVE = "核验前文十二项系统功能并给出结论";
    private static final String REQUIREMENTS = "十二项需求：时间同步、多模态记录、误差分析和版本对比";
    private static final String QUESTION = "请补充版本对比中使用的基线版本";
    private static final List<ChatMessage> HISTORY = List.of(
            UserMessage.from("请核验系统需求"), AiMessage.from(REQUIREMENTS));

    @AfterEach void clear() { PermissionContext.clear(); }

    @ParameterizedTest
    @CsvSource({"STEP_FAILURE,REWRITE_GRAPH", "STEP_FAILURE,REVISE_GOAL",
            "NO_READY,REWRITE_GRAPH", "GRAPH_ACCEPTANCE,REWRITE_GRAPH"})
    void runtimeRecoveryReceivesHistoryOriginalGoalAndCompletedReceipts(String entrance, FailureClass failureClass) {
        Fixture f = fixture(new PlannerStateStore(), entrance, failureClass);
        AtomicInteger invocations = new AtomicInteger();
        when(f.executor.execute(any(), anyString(), anyString(), any(), anyList(), anyInt(), any(), any()))
                .thenAnswer(call -> "STEP_FAILURE".equals(entrance) && invocations.getAndIncrement() == 0
                        ? failure() : success());
        ChatModel model = model(json(false, false));

        PlanningLoop.RunOutcome result = run(f, model, "继续", HISTORY);

        assertTrue(result.goalSatisfied(), result.reason() + ": " + result.answer());
        assertCorrectionContainsCurrentContext(model);
        StateSnapshot completed = f.store.get(SESSION).orElseThrow();
        assertEquals(EXECUTION, completed.executionId());
        assertEquals(f.originalCompleted, completed.graph().byId("completed"));
        assertEquals(TaskNodeStatus.SUCCESS, completed.graph().byId("rp1_finish").status());
        assertEquals(1, completed.recoveryCount());
        assertTrue(f.store.events(SESSION).stream().anyMatch(event ->
                event.type() == DomainEventType.RECOVERY_APPLIED && Boolean.TRUE.equals(event.payload().get("llmReplan"))));
        assertEquals("NO_READY".equals(entrance) ? 1 : 2, invocations(f.executor));
    }

    @Test
    void runtimeClarificationWaitPersistsSeparatelyAndReplyReusesCompletedActionWithoutReplay() throws Exception {
        Fixture first = fixture(new PlannerStateStore(), "STEP_FAILURE", FailureClass.REVISE_GOAL);
        when(first.executor.execute(any(), anyString(), anyString(), any(), anyList(), anyInt(), any(), any()))
                .thenReturn(failure());
        ChatModel question = model(json(true, false));

        PlanningLoop.RunOutcome waiting = run(first, question, "继续", HISTORY);

        assertEquals(PlanningLoop.Outcome.BLOCKED_ON_HUMAN, waiting.outcome());
        assertEquals("INTENT_NEEDS_CLARIFICATION", waiting.reason());
        assertEquals(QUESTION, waiting.answer());
        StateSnapshot suspended = first.store.get(SESSION).orElseThrow();
        assertEquals(EXECUTION, suspended.executionId());
        assertEquals(1, suspended.graph().nodes().size());
        assertTrue(GoalCompiler.isClarificationWait(suspended.graph(), suspended.intent()));
        assertNotNull(suspended.execution().get(PlanningLoop.RECOVERY_CLARIFICATION));
        assertEquals(1, invocations(first.executor));
        assertCorrectionContainsCurrentContext(question);

        StateSnapshot wire = PlannerStateJson.MAPPER.readValue(
                PlannerStateJson.MAPPER.writeValueAsString(suspended), StateSnapshot.class);
        Fixture resumed = restoredFixture(wire);
        when(resumed.executor.execute(any(), anyString(), anyString(), any(), anyList(), anyInt(), any(), any()))
                .thenReturn(success());
        ChatModel answer = model(json(false, true)); // The model repeats an already accepted action.
        PlanningLoop.RunOutcome done = run(resumed, answer, "基线版本是 v1，按前述需求继续核验", HISTORY);

        assertTrue(done.goalSatisfied(), done.reason() + ": " + done.answer());
        StateSnapshot completed = resumed.store.get(SESSION).orElseThrow();
        assertEquals(EXECUTION, completed.executionId());
        assertEquals(first.originalCompleted, completed.graph().byId("completed"));
        assertFalse(completed.execution().containsKey(PlanningLoop.RECOVERY_CLARIFICATION));
        assertEquals(1, completed.graph().nodes().stream().filter(node -> node.id().equals("completed")).count());
        assertEquals(1, invocations(resumed.executor), "only the unfinished verification may execute");
        assertTrue(completed.planVersion() > wire.planVersion());
        verify(resumed.lock, atLeastOnce()).renewSessionLock(SESSION, "fence");
    }

    @Test
    void corruptRecoveryReceiptCannotDiscardCompletedActionsOrExecuteAReplacement() {
        Fixture first = fixture(new PlannerStateStore(), "STEP_FAILURE", FailureClass.REVISE_GOAL);
        when(first.executor.execute(any(), anyString(), anyString(), any(), anyList(), anyInt(), any(), any()))
                .thenReturn(failure());
        run(first, model(json(true, false)), "继续", HISTORY);
        StateSnapshot waiting = first.store.get(SESSION).orElseThrow();
        Map<String, Object> metadata = new java.util.HashMap<>(waiting.execution());
        metadata.put(PlanningLoop.RECOVERY_CLARIFICATION, "{broken-json");
        StateSnapshot corrupt = first.store.commit(SESSION, waiting.version(), waiting.withExecution(metadata));
        clearInvocations(first.executor);
        ChatModel replacement = model(json(false, false));

        PlanningLoop.RunOutcome rejected = run(first, replacement, "基线版本为 v1", HISTORY);

        assertEquals("RECOVERY_CONTEXT_INVALID", rejected.reason());
        assertEquals(corrupt, first.store.get(SESSION).orElseThrow());
        verifyNoInteractions(replacement, first.executor);
    }

    @Test
    void executionReplacementDuringRuntimeCompileCannotAdoptLateRecoveryOrReplayCompletedActions() {
        Fixture f = fixture(new PlannerStateStore(), "STEP_FAILURE", FailureClass.REWRITE_GRAPH);
        when(f.executor.execute(any(), anyString(), anyString(), any(), anyList(), anyInt(), any(), any()))
                .thenReturn(failure());
        ChatModel model = model(json(false, false));
        when(model.chat(any(ChatRequest.class))).thenAnswer(call -> {
            StateSnapshot old = f.store.get(SESSION).orElseThrow();
            f.store.init(SESSION, "replacement-execution", old.goal(), new TaskGraph(List.of(
                    node("replacement", TaskNodeStatus.PENDING, List.of(), "replacement-output"))));
            return response(json(false, false));
        });

        PlanningLoop.RunOutcome rejected = run(f, model, "继续", HISTORY);

        assertEquals("STALE_EXECUTION", rejected.reason());
        StateSnapshot replacement = f.store.get(SESSION).orElseThrow();
        assertEquals("replacement-execution", replacement.executionId());
        assertEquals(TaskNodeStatus.PENDING, replacement.graph().byId("replacement").status());
        assertEquals(1, invocations(f.executor));
        assertFalse(f.store.events(SESSION).stream().anyMatch(event ->
                event.type() == DomainEventType.RECOVERY_APPLIED && Boolean.TRUE.equals(event.payload().get("llmReplan"))));
    }

    @Test
    void unknownEffectNeverEntersRuntimeReplanning() {
        Fixture f = fixture(new PlannerStateStore(), "STEP_FAILURE", FailureClass.REWRITE_GRAPH);
        StateSnapshot original = f.store.get(SESSION).orElseThrow();
        f.store.commit(SESSION, original.version(), original.withGraph(original.graph().replace(
                original.graph().byId("work").withStatus(TaskNodeStatus.OUTCOME_UNKNOWN)
                        .withToolStatus(com.miniagent.agent.tool.ToolStatus.UNKNOWN))));
        ChatModel model = model(json(false, false));

        assertEquals(AgentLoop.OUTCOME_UNKNOWN, run(f, model, "继续", HISTORY).reason());
        verifyNoInteractions(model, f.executor);
    }

    @ParameterizedTest
    @CsvSource({"true", "false"})
    void runtimeRecoveryRejectsChangedQueryWithSameOutputInsteadOfAdoptingOldEvidence(boolean sameId)
            throws Exception {
        Fixture f = fixture(new PlannerStateStore(), "STEP_FAILURE", FailureClass.REWRITE_GRAPH, "查询 A");
        TaskNode completedA = f.originalCompleted;
        when(f.executor.execute(any(), anyString(), anyString(), any(), anyList(), anyInt(), any(), any()))
                .thenReturn(failure(), success());
        var replacement = PlannerStateJson.MAPPER.readTree(json(false, true));
        var queryB = (com.fasterxml.jackson.databind.node.ObjectNode) replacement.path("nodes").get(0);
        queryB.put("name", "查询 B");
        if (!sameId) {
            queryB.put("id", "query-b");
            ((com.fasterxml.jackson.databind.node.ObjectNode) replacement.path("nodes").get(1))
                    .putArray("dependsOn").add("query-b");
        }
        ChatModel model = model(replacement.toString());

        PlanningLoop.RunOutcome rejected = run(f, model, "继续", HISTORY);

        assertFalse(rejected.goalSatisfied(), "a changed query cannot be satisfied by query A's receipt");
        assertEquals(1, invocations(f.executor), "a colliding replan must not execute or replay any action");
        verify(model).chat(any(ChatRequest.class));
        StateSnapshot retained = f.store.get(SESSION).orElseThrow();
        assertEquals(EXECUTION, retained.executionId());
        assertEquals(completedA, retained.graph().byId("completed"));
        assertNull(retained.graph().byId("rp1_finish"));
        assertFalse(f.store.events(SESSION).stream().anyMatch(event ->
                event.type() == DomainEventType.RECOVERY_APPLIED
                        && Boolean.TRUE.equals(event.payload().get("llmReplan"))));
    }

    private static Fixture fixture(PlannerStateStore store, String entrance, FailureClass failureClass) {
        return fixture(store, entrance, failureClass, "completed");
    }

    private static Fixture fixture(PlannerStateStore store, String entrance, FailureClass failureClass,
                                   String completedName) {
        PlannerProperties properties = new PlannerProperties();
        properties.setMaxOuterRounds(8);
        properties.setMaxRecoveries(3);
        properties.setMaxReplanRetries(0);
        properties.setCompilerRetry(0);
        TaskNode completed = new TaskNode("completed", completedName, "web", List.of(), List.of(),
                List.of("completed-evidence"), TaskNodeStatus.SUCCESS, 50, DoneWhen.note(), "", "", 0, "")
                .withOutput("已确认的执行回执", Map.of("completed-evidence", "已确认的执行回执"));
        Goal goal = new Goal("goal", OBJECTIVE, "", Map.of(), List.of(), List.of());
        TaskNode work = node("work", TaskNodeStatus.PENDING, List.of("completed"), "old-work");
        store.init(SESSION, EXECUTION, goal, new TaskGraph(List.of(completed, work)));
        Fixture fixture = components(properties, store, completed);
        FailureDiagnosis diagnosis = new FailureDiagnosis(failureClass, FailureKind.GOAL_BLOCKED,
                "work", "web_search", "需要按原需求修正", "重写剩余核验步骤");
        doReturn(diagnosis).when(fixture.recovery).diagnose(any(), anyString(), anyString());
        doReturn(diagnosis).when(fixture.recovery).diagnose(any(), anyString(), anyString(), any());
        if ("NO_READY".equals(entrance)) {
            AtomicInteger calls = new AtomicInteger();
            doAnswer(call -> calls.getAndIncrement() == 0 ? List.of()
                    : ((TaskGraph) call.getArgument(0)).readyNodes()).when(fixture.scheduler).select(any());
        }
        if ("GRAPH_ACCEPTANCE".equals(entrance)) {
            doReturn(StepEvaluator.GraphEval.fail("work", "核验需要修正"), StepEvaluator.GraphEval.pass())
                    .when(fixture.evaluator).evaluateGraph(any(), any(), any());
        }
        return fixture;
    }

    private static Fixture restoredFixture(StateSnapshot wire) {
        PlannerStateStore store = new PlannerStateStore();
        StateSnapshot base = store.init(SESSION, wire.executionId(), wire.goal(), wire.graph(), wire.intent(),
                wire.compilationSource(), wire.degraded());
        StateSnapshot restored = new StateSnapshot(base.version(), base.sessionId(), base.executionId(),
                wire.goal(), wire.graph(), wire.execution(), wire.environment(), wire.knowledgeRefs(),
                wire.recoveryCount(), wire.planRevision(), wire.intent(), wire.compilationSource(), wire.degraded());
        store.commit(SESSION, base.version(), restored);
        PlannerProperties properties = new PlannerProperties();
        properties.setMaxOuterRounds(8);
        properties.setMaxReplanRetries(0);
        properties.setCompilerRetry(0);
        return components(properties, store, null);
    }

    private static Fixture components(PlannerProperties properties, PlannerStateStore store, TaskNode completed) {
        NodeExecutor executor = mock(NodeExecutor.class);
        CapabilityRegistry capabilities = mock(CapabilityRegistry.class);
        when(capabilities.toolsFor(anyString())).thenReturn(List.of("web_search"));
        SessionLock lock = mock(SessionLock.class);
        when(lock.renewSessionLock(anyString(), anyString())).thenReturn(true);
        PlannerMetrics metrics = new PlannerMetrics();
        RecoveryEngine recovery = spy(new RecoveryEngine(store, capabilities, properties));
        StepEvaluator evaluator = spy(new StepEvaluator(properties, metrics, null));
        GraphScheduler scheduler = spy(new GraphScheduler(properties));
        return new Fixture(new PlanningLoop(properties, new GoalCompiler(properties), new PlanValidator(), store,
                scheduler, new ToolRouter(capabilities), evaluator, recovery, mock(TodoStateProjector.class),
                executor, metrics, lock, new ToolSuccessStats()), store, executor, recovery, scheduler,
                evaluator, lock, completed);
    }

    private static String json(boolean question, boolean repeatCompleted) {
        var root = PlannerStateJson.MAPPER.createObjectNode();
        root.put("objective", OBJECTIVE).put("taskType", "信息检索");
        root.putArray("constraints"); root.putArray("successCriteria");
        var intent = root.putObject("intent");
        intent.put("objective", OBJECTIVE).put("needsClarification", question)
                .put("clarificationQuestion", question ? QUESTION : "");
        intent.putArray("deliverables"); intent.putArray("inputs"); intent.putArray("constraints");
        intent.putArray("exclusions"); intent.putArray("acceptanceCriteria");
        var nodes = root.putArray("nodes");
        if (!question) {
            if (repeatCompleted) {
                var previous = nodes.addObject().put("id", "completed").put("name", "completed").put("capability", "web");
                previous.putArray("dependsOn"); previous.putArray("inputs");
                previous.putArray("outputs").add("completed-evidence");
                previous.putObject("doneWhen").put("type", "note_required");
            }
            var finish = nodes.addObject().put("id", "finish").put("name", "完成剩余核验").put("capability", "web");
            if (repeatCompleted) finish.putArray("dependsOn").add("completed");
            else finish.putArray("dependsOn");
            finish.putArray("inputs");
            finish.putArray("outputs").add("verification-evidence");
            finish.putObject("doneWhen").put("type", "note_required");
        }
        return root.toString();
    }

    private static void assertCorrectionContainsCurrentContext(ChatModel model) {
        ArgumentCaptor<ChatRequest> request = ArgumentCaptor.forClass(ChatRequest.class);
        verify(model).chat(request.capture());
        String text = ((UserMessage) request.getValue().messages().getLast()).contents().stream()
                .filter(TextContent.class::isInstance).map(TextContent.class::cast).map(TextContent::text)
                .collect(java.util.stream.Collectors.joining("\n"));
        assertTrue(text.contains(REQUIREMENTS));
        assertTrue(text.contains(OBJECTIVE));
        assertTrue(text.contains("completed-evidence"));
        assertTrue(text.contains("已确认的执行回执"));
    }

    private static PlanningLoop.RunOutcome run(Fixture f, ChatModel model, String request, List<ChatMessage> history) {
        return f.loop.runWithOutcome(model, "system", request, null, history,
                TaskPlan.of(OBJECTIVE).withArtifactDeliveryRequested(false), SESSION, "fence", "new-ui-turn", null,
                PlanningRoute.dag("state", "RESUME_CURRENT_GRAPH"));
    }

    private static ChatModel model(String json) {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenReturn(response(json));
        return model;
    }

    private static ChatResponse response(String json) {
        return ChatResponse.builder().aiMessage(AiMessage.from(json)).build();
    }

    private static TaskNode node(String id, TaskNodeStatus status, List<String> deps, String output) {
        return new TaskNode(id, id, "web", deps, List.of(), List.of(output), status, 50,
                DoneWhen.note(), "", "", 0, "");
    }

    private static AgentLoop.LoopOutcome failure() {
        return new AgentLoop.LoopOutcome("目标需要进一步核实", "FAILURE", List.of(),
                List.of("web_search"), ToolErrorCode.EXECUTION_FAILED);
    }

    private static AgentLoop.LoopOutcome success() {
        ToolResult result = ToolResult.success("核验通过：已根据十二项需求检查，基线v1与当前结果一致。");
        return new AgentLoop.LoopOutcome(result.legacyText(), "SUCCESS",
                List.of(ToolExecutionResultMessage.from("lookup", "web_search", result.legacyText())),
                List.of("web_search"), ToolErrorCode.NONE, result);
    }

    private static int invocations(NodeExecutor executor) {
        return (int) mockingDetails(executor).getInvocations().stream()
                .filter(call -> call.getMethod().getName().equals("execute")).count();
    }

    private record Fixture(PlanningLoop loop, PlannerStateStore store, NodeExecutor executor,
                           RecoveryEngine recovery, GraphScheduler scheduler, StepEvaluator evaluator,
                           SessionLock lock, TaskNode originalCompleted) {}
}
