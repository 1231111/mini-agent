package com.miniagent.agent.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.agent.execution.InMemoryActionJournal;
import com.miniagent.agent.execution.AgentToolsProperties;
import com.miniagent.agent.execution.ToolExecutionGuards;
import com.miniagent.agent.execution.ToolPipeline;
import com.miniagent.agent.hook.StopHookChain;
import com.miniagent.agent.hook.ToolHookChain;
import com.miniagent.agent.hook.TodoPlanStopHook;
import com.miniagent.agent.permission.ConfirmPolicy;
import com.miniagent.agent.permission.ExecPolicyService;
import com.miniagent.agent.permission.PermissionContext;
import com.miniagent.agent.permission.PermissionMode;
import com.miniagent.agent.permission.SessionPermissionStore;
import com.miniagent.agent.planner.DoneWhen;
import com.miniagent.agent.planner.ProposalTurnPolicy;
import com.miniagent.agent.planner.TaskGraph;
import com.miniagent.agent.planner.TaskNode;
import com.miniagent.agent.planner.TaskNodeStatus;
import com.miniagent.agent.planner.TodoStateProjector;
import com.miniagent.agent.planner.DomainEvent;
import com.miniagent.agent.planner.DomainEventType;
import com.miniagent.agent.planner.Goal;
import com.miniagent.agent.planner.GoalCompiler;
import com.miniagent.agent.planner.GraphScheduler;
import com.miniagent.agent.planner.NodeTerminalCommitPort;
import com.miniagent.agent.planner.PlanValidator;
import com.miniagent.agent.planner.PlannerMetrics;
import com.miniagent.agent.planner.PlannerProperties;
import com.miniagent.agent.planner.PlannerStateStore;
import com.miniagent.agent.planner.PlanningLoop;
import com.miniagent.agent.planner.PlanningRoute;
import com.miniagent.agent.planner.RecoveryEngine;
import com.miniagent.agent.planner.SessionLock;
import com.miniagent.agent.planner.StateSnapshot;
import com.miniagent.agent.planner.StepEvaluator;
import com.miniagent.agent.planner.ToolRouter;
import com.miniagent.agent.planner.ToolSuccessStats;
import com.miniagent.agent.surface.ToolSurface;
import com.miniagent.agent.task.TaskPlan;
import com.miniagent.agent.todo.TaskTodoContext;
import com.miniagent.agent.todo.TaskTodoStore;
import com.miniagent.agent.todo.TodoTool;
import com.miniagent.agent.todo.LlmJudgeTodoValidator;
import com.miniagent.agent.tool.CapabilityRegistry;
import com.miniagent.agent.tool.ToolErrorCode;
import com.miniagent.agent.tool.ToolRegistry;
import com.miniagent.agent.tool.ToolResult;
import com.miniagent.agent.tool.ToolStatus;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real loop/registry/pipeline/Todo integration; only the model and search service are offline substitutes. */
class AgentLoopDagEvidenceTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SESSION = "dag-evidence-integration";
    private static final String EVIDENCE = "Collected four dated source records and verified their publication dates.";
    private static final String ANSWER = "Four sources were checked; the dated findings are ready for validation.";

    @TempDir
    Path temp;

    @AfterEach
    void clearContexts() {
        AgentLoop.clearCurrentSession();
        AgentLoop.clearCurrentModels();
        TaskTodoContext.clear();
        TaskExecutionContext.clear();
        LoopTurnContext.clear();
        PermissionContext.clear();
        PermissionContext.bindStore(null);
        ExecutionTurnContext.clear();
    }

    @Test
    void legacyCompletedSubmitsEvidenceAndReturnsSuccessWithoutSelfApprovingDagNode() throws Exception {
        Fixture fixture = fixture(true, ToolResult.success(EVIDENCE));

        AgentLoop.LoopOutcome outcome = fixture.run(search(), legacyCompletion(EVIDENCE), answer());

        assertTrue(outcome.successful());
        assertEquals("SUCCESS", outcome.endReason());
        assertEquals(ANSWER, outcome.text());
        assertEquals(ToolStatus.SUCCESS, outcome.lastToolResult().status());
        JsonNode submission = fixture.lastTodoResult();
        assertTrue(submission.path("success").asBoolean());
        assertTrue(submission.path("pendingValidation").asBoolean());
        assertEquals("in_progress", submission.path("status").asText());
        assertEquals("completed", submission.path("requestedStatus").asText());
        assertEquals("planner", submission.path("completionOwner").asText());
        assertEvidencePending(fixture.store);
        assertEvidencePending(new TaskTodoStore(temp.resolve("todos").toString()));
        assertEquals(TaskNodeStatus.RUNNING, fixture.graph.byId("source-check").status());
        assertEquals(1, fixture.searchCalls.get(), "bookkeeping must not replay the actual search");

        // Only the scheduler's trusted success projection may publish completed to the UI.
        fixture.projector.project(SESSION, new TaskGraph(List.of(
                fixture.graph.byId("source-check").withStatus(TaskNodeStatus.SUCCESS).withOutput(EVIDENCE))));
        assertEquals(TaskTodoStore.Status.completed, fixture.store.get(SESSION).getFirst().status());
    }

    @ParameterizedTest
    @ValueSource(strings = {"legacy_completed", "submit"})
    void softDagReturnsToSchedulerWithoutLegacyIncompleteTodoNudges(String action) throws Exception {
        Fixture fixture = fixture(true, ToolResult.success(EVIDENCE));
        LoopTurnContext.set(new ProposalTurnPolicy(List.of("web_search", "todo"), false,
                "Verify four dated sources", Set.of(1)));

        AgentLoop.LoopOutcome outcome = fixture.run(search(), "submit".equals(action)
                ? submission(EVIDENCE) : legacyCompletion(EVIDENCE), answer());

        assertTrue(outcome.successful());
        assertEquals("SUCCESS", outcome.endReason());
        assertEquals(ANSWER, outcome.text());
        assertEquals(3, fixture.requests.size(), "DAG handoff must not request extra legacy completion turns");
        assertEquals(1, fixture.searchCalls.get());
        assertTrue(fixture.lastTodoResult().path("pendingValidation").asBoolean());
        assertEvidencePending(fixture.store);
        assertTrue(systemTexts(outcome.messages()).stream().noneMatch(text -> text.contains("【收尾被拦截】")));
        String subgoal = systemTexts(fixture.requests.getFirst()).stream()
                .filter(text -> text.startsWith("当前子目标")).findFirst().orElseThrow();
        assertTrue(subgoal.contains("action=submit"));
        assertFalse(subgoal.contains("标记为 completed 再进入下一步"));
    }

    @Test
    void successfulEvidenceSubmissionCannotHideFailedRealSearch() throws Exception {
        ToolResult searchFailure = ToolResult.failure(ToolErrorCode.INVALID_ARGUMENT, "Synthetic search failure", false);
        Fixture fixture = fixture(true, searchFailure);

        AgentLoop.LoopOutcome outcome = fixture.run(search(), submission(EVIDENCE), answer());

        assertFalse(outcome.successful());
        assertEquals("FAILURE", outcome.endReason());
        assertEquals(ToolStatus.FAILED, outcome.lastToolResult().status());
        assertEquals(ToolErrorCode.INVALID_ARGUMENT, outcome.lastToolResult().errorCode());
        assertEquals("Synthetic search failure", outcome.lastToolResult().message());
        assertTrue(fixture.lastTodoResult().path("success").asBoolean());
        assertEvidencePending(fixture.store);
        assertEquals(1, fixture.searchCalls.get());
    }

    @Test
    void correctedSubmissionClearsOnlyBookkeepingFailureAndRestoresSuccessfulAction() throws Exception {
        Fixture fixture = fixture(true, ToolResult.success(EVIDENCE));

        AgentLoop.LoopOutcome outcome = fixture.run(search(), legacyCompletion(null), submission(EVIDENCE), answer());

        assertEquals(4, fixture.requests.size());
        JsonNode invalid = todoResult(fixture.requests.get(2));
        assertFalse(invalid.path("success").asBoolean());
        assertTrue(invalid.path("error").asText().contains("evidence"));
        assertTrue(fixture.lastTodoResult().path("success").asBoolean());
        assertTrue(outcome.successful(), "a corrected Todo request must recover from its own validation error");
        assertEquals("SUCCESS", outcome.endReason());
        assertEquals(EVIDENCE, outcome.lastToolResult().legacyText(), "the last actual action remains the execution result");
        assertEvidencePending(fixture.store);
        assertEquals(1, fixture.searchCalls.get());
    }

    @Test
    void uncorrectedEvidenceErrorStillPreventsSuccessfulLoopOutcome() throws Exception {
        Fixture fixture = fixture(true, ToolResult.success(EVIDENCE));

        AgentLoop.LoopOutcome outcome = fixture.run(search(), legacyCompletion(null), answer());

        assertFalse(outcome.successful());
        assertEquals("FAILURE", outcome.endReason());
        assertFalse(fixture.lastTodoResult().path("success").asBoolean());
        assertEquals(TaskTodoStore.Status.in_progress, fixture.store.get(SESSION).getFirst().status());
        assertTrue(fixture.store.get(SESSION).getFirst().evidence().isBlank());
    }

    @Test
    void successfulBookkeepingWithoutAnActionDoesNotBecomeAnExecutionArtifact() throws Exception {
        Fixture fixture = fixture(true, ToolResult.success(EVIDENCE));

        AgentLoop.LoopOutcome outcome = fixture.run(submission(EVIDENCE), answer());

        assertTrue(outcome.successful());
        assertNull(outcome.lastToolResult(), "pendingValidation is a coordination receipt, not an execution artifact");
        assertEquals(ToolErrorCode.NONE, outcome.lastErrorCode());
        assertTrue(fixture.lastTodoResult().path("pendingValidation").asBoolean());
        assertEvidencePending(fixture.store);
        assertEquals(0, fixture.searchCalls.get());
    }

    @Test
    void dagTaskAndSubgoalPromptsRequireEvidenceSubmissionAndSchedulerHandoff() {
        Fixture fixture = fixture(true, ToolResult.success(EVIDENCE));

        fixture.run(answer());

        List<String> systems = systemTexts(fixture.requests.getFirst());
        String taskPrompt = systems.stream().filter(text -> text.contains("# 本轮任务参数")).findFirst().orElseThrow();
        String graphPrompt = systems.stream().filter(text -> text.contains("【规划器已投影任务图】")).findFirst().orElseThrow();
        String subgoalPrompt = systems.stream().filter(text -> text.startsWith("当前子目标")).findFirst().orElseThrow();
        for (String prompt : List.of(taskPrompt, graphPrompt, subgoalPrompt)) {
            assertTrue(prompt.contains("action=submit"), prompt);
            assertTrue(prompt.contains("交回调度器"), prompt);
            assertTrue(prompt.contains("状态保持 in_progress"), prompt);
            assertFalse(prompt.contains("标记为 completed 再进入下一步"), prompt);
        }
    }

    @Test
    void legacyChecklistKeepsCompletedWorkflowAndSubgoalPrompt() throws Exception {
        Fixture fixture = fixture(false, ToolResult.success(EVIDENCE));

        AgentLoop.LoopOutcome outcome = fixture.run(search(), legacyCompletion(EVIDENCE), answer());

        String subgoalPrompt = systemTexts(fixture.requests.getFirst()).stream()
                .filter(text -> text.startsWith("当前子目标")).findFirst().orElseThrow();
        assertTrue(subgoalPrompt.contains("标记为 completed 再进入下一步"));
        assertFalse(subgoalPrompt.contains("action=submit"));
        assertTrue(outcome.successful());
        assertFalse(fixture.store.hasDagManagedPlan(SESSION));
        assertEquals(TaskTodoStore.Status.completed, fixture.store.get(SESSION).getFirst().status());
        assertEquals(EVIDENCE, fixture.store.get(SESSION).getFirst().evidence());
        assertFalse(fixture.lastTodoResult().path("pendingValidation").asBoolean());
    }

    @Test
    void unknownRealActionStopsBeforeFollowingTodoCanSubmitEvidence() {
        Fixture fixture = fixture(true, ToolResult.unknown("Synthetic unacknowledged action", null));
        AiMessage actions = AiMessage.from(List.of(search().toolExecutionRequests().getFirst(),
                submission(EVIDENCE).toolExecutionRequests().getFirst()));

        AgentLoop.LoopOutcome outcome = fixture.run(actions, answer());

        assertFalse(outcome.successful());
        assertEquals(AgentLoop.OUTCOME_UNKNOWN, outcome.endReason());
        assertEquals(ToolStatus.UNKNOWN, outcome.lastToolResult().status());
        assertEquals(1, fixture.requests.size(), "unknown outcome cannot be cleared by another model turn");
        assertEquals(1, fixture.searchCalls.get());
        assertEquals(TaskTodoStore.Status.in_progress, fixture.store.get(SESSION).getFirst().status());
        assertTrue(fixture.store.get(SESSION).getFirst().evidence().isBlank());
        assertTrue(outcome.messages().stream().filter(ToolExecutionResultMessage.class::isInstance)
                .map(ToolExecutionResultMessage.class::cast).noneMatch(result -> "todo".equals(result.toolName())));
    }

    @Test
    void realPlannerAndLoopAcceptSearchedEvidenceAfterLegacyCompletionAndPublishOnlyAcceptedAnswer() throws Exception {
        assertPlannerAcceptance(true);
    }

    @Test
    void realPlannerAndLoopAcceptSynthesisBodyInsteadOfTodoProtocolJson() throws Exception {
        assertPlannerAcceptance(false);
    }

    private void assertPlannerAcceptance(boolean searchRequired) throws Exception {
        Fixture fixture = fixture(true, ToolResult.success(EVIDENCE));
        String body = searchRequired ? ANSWER : "The supplied values are 2 and 3; their sum is 5.";
        String evidence = searchRequired ? EVIDENCE : body;
        AtomicInteger successCommits = new AtomicInteger();
        AtomicInteger graphAcceptanceCommits = new AtomicInteger();
        PlannerStateStore state = new PlannerStateStore() {
            @Override public StateSnapshot commit(String session, long version, StateSnapshot next, List<DomainEvent> events) {
                if (events.stream().anyMatch(event -> event.type() == DomainEventType.NODE_SUCCESS)) {
                    assertEquals(TaskNodeStatus.RUNNING, get(session).orElseThrow().graph().byId("source-check").status());
                    assertEquals(TaskTodoStore.Status.in_progress, fixture.store.get(SESSION).getFirst().status(),
                            "the model's completion request cannot publish completed before scheduler acceptance");
                    assertEquals(evidence, fixture.store.get(SESSION).getFirst().evidence());
                    assertEquals(TaskNodeStatus.SUCCESS, next.graph().byId("source-check").status());
                    successCommits.incrementAndGet();
                }
                if (next.execution().containsKey("graphAcceptedPlanVersion")) {
                    assertEquals(TaskNodeStatus.SUCCESS, next.graph().byId("source-check").status());
                    graphAcceptanceCommits.incrementAndGet();
                }
                return super.commit(session, version, next, events);
            }
        };
        PlannerProperties properties = new PlannerProperties();
        properties.setMaxOuterRounds(5);
        properties.setMaxRecoveries(0);
        TaskNode node = new TaskNode("source-check", searchRequired ? "Verify dated sources" : "Add the supplied values",
                searchRequired ? "web" : "synthesis", List.of(), List.of(), List.of("answer"),
                TaskNodeStatus.PENDING, 0, DoneWhen.judge("Provide the requested answer from the supplied evidence"),
                "", "", 0, "").withProcedure(42L, 1);
        state.init(SESSION, "evidence-integration-execution", new Goal("goal", searchRequired
                ? "Verify four dated sources" : "Given 2 and 3, calculate their sum", "", Map.of(), List.of(), List.of()),
                new TaskGraph(List.of(node)));
        CapabilityRegistry capabilities = mock(CapabilityRegistry.class);
        when(capabilities.toolsFor(anyString())).thenReturn(searchRequired ? List.of("web_search") : List.of());
        SessionLock lease = mock(SessionLock.class);
        when(lease.renewSessionLock(anyString(), anyString())).thenReturn(true);
        PlannerMetrics metrics = new PlannerMetrics();
        LlmJudgeTodoValidator judge = mock(LlmJudgeTodoValidator.class);
        when(judge.validate(any(), anyString())).thenAnswer(call -> {
            String actual = call.getArgument(1);
            assertTrue(actual.contains(evidence), "judge must evaluate actual observations or synthesis prose, not Todo bookkeeping");
            assertFalse(actual.contains("pendingValidation"));
            assertFalse(actual.contains("INTERNAL_NODE_DRAFT"));
            return null;
        });
        NodeTerminalCommitPort feedback = mock(NodeTerminalCommitPort.class);
        doAnswer(call -> {
            assertEquals(1, successCommits.get(), "feedback follows the successful CAS");
            assertEquals(TaskNodeStatus.SUCCESS, state.get(SESSION).orElseThrow().graph().byId("source-check").status());
            assertEquals(1, state.events(SESSION).stream().filter(event -> event.type() == DomainEventType.NODE_SUCCESS).count());
            return null;
        }).when(feedback).commit(anyString(), anyLong(), anyString(), any(), eq(NodeTerminalCommitPort.Result.SUCCESS), any());
        PlanningLoop planner = new PlanningLoop(properties, mock(GoalCompiler.class), new PlanValidator(), state,
                new GraphScheduler(properties), new ToolRouter(capabilities), new StepEvaluator(properties, metrics, judge),
                new RecoveryEngine(state, capabilities, properties), fixture.projector,
                new NodeExecutor(fixture.loop, fixture.pipeline), metrics, lease, new ToolSuccessStats());
        ReflectionTestUtils.setField(planner, "nodeTerminalCommitPort", feedback);
        AgentStreamSink user = mock(AgentStreamSink.class);
        AiMessage first = searchRequired
                ? AiMessage.from("INTERNAL_NODE_DRAFT", search().toolExecutionRequests())
                : submission(evidence);
        ChatModel model = searchRequired
                ? fixture.model(first, legacyCompletion(evidence), AiMessage.from(body))
                : fixture.model(first, AiMessage.from(body));

        PlanningLoop.RunOutcome outcome = planner.runWithOutcome(model, "Execute the requested task.", "继续", null,
                List.of(), TaskPlan.of(searchRequired ? "Verify dated sources" : "Given 2 and 3, calculate their sum"),
                SESSION, "synthetic-fence", "unused-resume-execution", user,
                PlanningRoute.dag("state", "RESUME_CURRENT_GRAPH"));

        assertTrue(outcome.goalSatisfied(), outcome.answer());
        TaskNode persisted = state.get(SESSION).orElseThrow().graph().byId("source-check");
        assertEquals(TaskNodeStatus.SUCCESS, persisted.status());
        assertTrue(persisted.output().contains(evidence));
        if (searchRequired) {
            assertTrue(outcome.answer().contains(EVIDENCE));
            assertFalse(outcome.answer().contains(body), "unverified model prose cannot replace accepted source observations");
        } else {
            assertEquals(body, outcome.answer());
            assertEquals(body, persisted.output());
        }
        assertFalse(persisted.output().contains("pendingValidation"));
        assertFalse(outcome.answer().contains("pendingValidation"));
        assertFalse(outcome.answer().contains("INTERNAL_NODE_DRAFT"));
        assertEquals(TaskTodoStore.Status.completed, fixture.store.get(SESSION).getFirst().status());
        assertEquals(1, successCommits.get());
        assertEquals(1, graphAcceptanceCommits.get());
        assertEquals(searchRequired ? 1 : 0, fixture.searchCalls.get());
        assertEquals(searchRequired ? 3 : 2, fixture.requests.size());
        verify(feedback, times(1)).commit(anyString(), anyLong(), anyString(), any(),
                eq(NodeTerminalCommitPort.Result.SUCCESS), any());
        verify(judge, times(2)).validate(any(), anyString());
        verify(user, never()).onAnswerToken(anyString());
        verify(user, never()).onAnswerReset();
        assertTrue(fixture.lastTodoResult().path("pendingValidation").asBoolean());
    }

    private Fixture fixture(boolean dag, ToolResult searchResult) {
        TaskTodoStore store = new TaskTodoStore(temp.resolve("todos").toString());
        TodoStateProjector projector = new TodoStateProjector(store);
        TaskNode node = new TaskNode("source-check", "Verify four dated sources", "web", null, null, null,
                TaskNodeStatus.RUNNING, 0, DoneWhen.note(), "", "", 0, "");
        TaskGraph graph = new TaskGraph(List.of(node));
        if (dag) {
            projector.project(SESSION, graph);
        } else {
            store.set(SESSION, List.of(Map.of("id", 1, "content", node.name(), "status", "in_progress",
                    "done_when", "note_required")));
        }
        ToolRegistry registry = new ToolRegistry();
        AtomicInteger searchCalls = new AtomicInteger();
        registry.registerStructured("web_search", "Offline synthetic search", Map.of(), arguments -> {
            searchCalls.incrementAndGet();
            return searchResult;
        });
        TodoTool todo = new TodoTool();
        ReflectionTestUtils.setField(todo, "toolRegistry", registry);
        ReflectionTestUtils.setField(todo, "todoStore", store);
        todo.register();
        ExecutionControl control = new ExecutionControl(60_000, 20, 10_000);
        ToolExecutionGuards guards = new ToolExecutionGuards(registry, new AgentToolsProperties(4));
        SessionPermissionStore permissions = new SessionPermissionStore();
        ToolPipeline pipeline = new ToolPipeline(control, new ToolHookChain(new ArrayList<>()), permissions,
                new InMemoryActionJournal(), guards, registry, new ExecPolicyService("allow", "", permissions));
        ToolSurface surface = new ToolSurface();
        ReflectionTestUtils.setField(surface, "toolRegistry", registry);
        ContextCompressor compressor = mock(ContextCompressor.class);
        when(compressor.maybeCompress(anyList(), anyInt(), anyString(), anyInt()))
                .thenAnswer(invocation -> ContextCompressor.CompressResult.untouched(invocation.getArgument(0), 1));
        AgentLoop loop = new AgentLoop();
        ReflectionTestUtils.setField(loop, "toolRegistry", registry);
        ReflectionTestUtils.setField(loop, "toolSurface", surface);
        ReflectionTestUtils.setField(loop, "toolExecutionGuards", guards);
        ReflectionTestUtils.setField(loop, "toolPipeline", pipeline);
        ReflectionTestUtils.setField(loop, "executionControl", control);
        ReflectionTestUtils.setField(loop, "executionProperties", new ExecutionProperties());
        ReflectionTestUtils.setField(loop, "contextCompressor", compressor);
        ReflectionTestUtils.setField(loop, "taskTodoStore", store);
        ReflectionTestUtils.setField(loop, "stopHookChain", new StopHookChain(List.of(new TodoPlanStopHook())));
        AgentLoop.setCurrentSession(SESSION);
        TaskTodoContext.set(SESSION);
        PermissionContext.force(SESSION, PermissionMode.ACCEPT_EDITS, ConfirmPolicy.AUTO, true);
        if (dag) {
            LoopTurnContext.set(new ProposalTurnPolicy(List.of("web_search", "todo"), true, node.name(), Set.of(1)));
        }
        return new Fixture(loop, pipeline, store, projector, graph, searchCalls);
    }

    private static void assertEvidencePending(TaskTodoStore store) {
        TaskTodoStore.TodoItem item = store.get(SESSION).getFirst();
        assertEquals(TaskTodoStore.Status.in_progress, item.status());
        assertEquals(EVIDENCE, item.evidence());
        assertTrue(store.isDagManaged(SESSION, item.id()));
    }

    private static AiMessage search() {
        return tool("search-call", "web_search", "{}");
    }

    private static AiMessage legacyCompletion(String evidence) {
        return tool("legacy-todo", "todo", evidence == null
                ? "{\"action\":\"update\",\"id\":1,\"status\":\"completed\"}"
                : "{\"action\":\"update\",\"id\":1,\"status\":\"completed\",\"evidence\":\"" + evidence + "\"}");
    }

    private static AiMessage submission(String evidence) {
        return tool("submit-todo", "todo", "{\"action\":\"submit\",\"id\":1,\"evidence\":\"" + evidence + "\"}");
    }

    private static AiMessage tool(String id, String name, String arguments) {
        return AiMessage.from(ToolExecutionRequest.builder().id(id).name(name).arguments(arguments).build());
    }

    private static AiMessage answer() {
        return AiMessage.from(ANSWER);
    }

    private static List<String> systemTexts(List<ChatMessage> messages) {
        return messages.stream().filter(SystemMessage.class::isInstance).map(SystemMessage.class::cast)
                .map(SystemMessage::text).toList();
    }

    private static JsonNode todoResult(List<ChatMessage> messages) throws Exception {
        List<ToolExecutionResultMessage> results = messages.stream().filter(ToolExecutionResultMessage.class::isInstance)
                .map(ToolExecutionResultMessage.class::cast).filter(result -> "todo".equals(result.toolName())).toList();
        return JSON.readTree(results.getLast().text());
    }

    private static class Fixture {
        final AgentLoop loop;
        final ToolPipeline pipeline;
        final TaskTodoStore store;
        final TodoStateProjector projector;
        final TaskGraph graph;
        final AtomicInteger searchCalls;
        final List<List<ChatMessage>> requests = new ArrayList<>();

        Fixture(AgentLoop loop, ToolPipeline pipeline, TaskTodoStore store, TodoStateProjector projector, TaskGraph graph,
                AtomicInteger searchCalls) {
            this.loop = loop;
            this.pipeline = pipeline;
            this.store = store;
            this.projector = projector;
            this.graph = graph;
            this.searchCalls = searchCalls;
        }

        AgentLoop.LoopOutcome run(AiMessage... responses) {
            return loop.runOutcome(model(responses), "Execute the current task and provide evidence.", "Verify the requested sources.",
                    List.of(), 6, TaskPlan.of("Verify four dated sources"), null);
        }

        ChatModel model(AiMessage... responses) {
            ChatModel model = mock(ChatModel.class);
            when(model.chat(any(ChatRequest.class))).thenAnswer(invocation -> {
                ChatRequest request = invocation.getArgument(0);
                requests.add(List.copyOf(request.messages()));
                int turn = requests.size() - 1;
                assertTrue(turn < responses.length, "unexpected extra model turn");
                return ChatResponse.builder().aiMessage(responses[turn]).build();
            });
            return model;
        }

        JsonNode lastTodoResult() throws Exception {
            return todoResult(requests.getLast());
        }
    }
}
