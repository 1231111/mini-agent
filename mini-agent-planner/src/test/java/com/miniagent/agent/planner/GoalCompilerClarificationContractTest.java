package com.miniagent.agent.planner;

import com.miniagent.agent.core.NodeExecutor;
import com.miniagent.agent.permission.PermissionContext;
import com.miniagent.agent.task.TaskPlan;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Clarification is a durable human wait, never an empty-plan success or an action approval. */
class GoalCompilerClarificationContractTest {
    private static final String SESSION = "clarification-session";
    private static final String REQUEST = "实现覆盖技术要求全部功能的静态系统前端";
    private static final String QUESTION = "请提供技术要求中的十二项功能清单";
    private static final String CONSTRAINT = "覆盖技术要求中的全部功能";

    @AfterEach
    void clearPermission() {
        PermissionContext.clear();
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void explicitQuestionBecomesOnlyAWaitingReceiptInBothCompilerPaths(boolean correction, boolean mixed) {
        PlannerProperties properties = properties();
        properties.setCompilerRetry(3);
        GoalCompiler compiler = new GoalCompiler(properties);
        ChatModel model = model(compilation(true, QUESTION, mixed));
        TaskPlan plan = plan(REQUEST);

        GoalCompiler.CompileResult result = correction
                ? compiler.compileWithCorrection(model, REQUEST, plan, "补齐必要信息", List.of())
                : compiler.compile(model, REQUEST, plan, List.of());

        assertEquals(GoalCompiler.CompilationSource.MODEL, result.source());
        assertTrue(result.goal().isClarify());
        assertEquals(REQUEST, result.goal().objective());
        assertEquals(QUESTION, result.intent().clarificationQuestion());
        assertEquals(List.of(CONSTRAINT), result.intent().constraints());
        assertEquals("index.html", result.intent().deliverables().getFirst().path());
        assertTrue(result.goal().successCriteria().contains("index.html"));
        assertEquals(1, result.graph().nodes().size());
        assertEquals(TaskNodeStatus.AWAITING_CONFIRM, result.graph().nodes().getFirst().status());
        assertTrue(result.graph().nodes().getFirst().toolArguments().isEmpty());
        assertFalse(result.graph().allTerminalSuccess());
        assertTrue(new PlanValidator().validate(result.graph(), plan, result.goal(), result.intent()).valid());
        assertTrue(new IntentGraphValidator().validate(result.intent(), result.graph()).valid());
        verify(model).chat(any(ChatRequest.class));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void missingQuestionCannotTurnExecutableNodesIntoAnAcceptedTask(String question) {
        GoalCompiler compiler = new GoalCompiler(properties());
        ChatModel model = model(compilation(true, question, true));

        GoalCompiler.CompileResult first = compiler.compile(model, REQUEST, plan(REQUEST));
        GoalCompiler.CompileResult corrected = compiler.compileWithCorrection(
                model, REQUEST, plan(REQUEST), "补齐具体问题");

        assertTrue(first.graph().isEmpty());
        assertTrue(corrected.graph().isEmpty());
        assertNotEquals(GoalCompiler.CompilationSource.MODEL, first.source());
        assertNotEquals(GoalCompiler.CompilationSource.MODEL, corrected.source());
        verify(model, times(2)).chat(any(ChatRequest.class));
    }

    @Test
    void oversizedQuestionIsRejectedRatherThanPersistedAsAWaitingReceipt() {
        GoalCompiler.CompileResult result = new GoalCompiler(properties()).compile(
                model(compilation(true, "问".repeat(2_001), false)), REQUEST, plan(REQUEST));
        assertTrue(result.graph().isEmpty());
        assertNotEquals(GoalCompiler.CompilationSource.MODEL, result.source());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void emptyGraphWithoutExplicitClarificationStillFails(boolean correction) {
        GoalCompiler compiler = new GoalCompiler(properties());
        ChatModel model = model(compilation(false, "", false));
        GoalCompiler.CompileResult result = correction
                ? compiler.compileWithCorrection(model, REQUEST, plan(REQUEST), "修正执行图")
                : compiler.compile(model, REQUEST, plan(REQUEST));
        assertTrue(result.graph().isEmpty());
        assertNotEquals(GoalCompiler.CompilationSource.MODEL, result.source());
    }

    @Test
    void serializedClarificationWaitCanRecompileWithOriginalObjectiveAndConversation() throws Exception {
        Fixture first = fixture(properties(), new PlannerStateStore());
        PlanningLoop.RunOutcome waiting = run(first, model(compilation(true, QUESTION, true)),
                REQUEST, List.of(AiMessage.from("可以继续实现界面或编写说明")), "first-execution", initialRoute());

        assertEquals(PlanningLoop.Outcome.BLOCKED_ON_HUMAN, waiting.outcome());
        assertEquals("INTENT_NEEDS_CLARIFICATION", waiting.reason());
        assertEquals(QUESTION, waiting.answer());
        StateSnapshot saved = first.store().get(SESSION).orElseThrow();
        StateSnapshot restored = PlannerStateJson.MAPPER.readValue(
                PlannerStateJson.MAPPER.writeValueAsString(saved), StateSnapshot.class);
        PlannerStateStore resumedStore = new PlannerStateStore();
        StateSnapshot base = resumedStore.init(SESSION, restored.executionId(), restored.goal(), restored.graph(),
                restored.intent(), restored.compilationSource(), restored.degraded());
        resumedStore.commit(SESSION, base.version(), base.withExecution(restored.execution()));
        PlannerProperties resumedProperties = properties();
        resumedProperties.setMaxOuterRounds(0); // Admit the replacement plan without running real tools.
        Fixture resumed = fixture(resumedProperties, resumedStore);
        String reply = "十二项功能包括多模态记录、时间同步、误差分析、版本对比等，按前述清单实现";
        List<ChatMessage> history = List.of(UserMessage.from(REQUEST), AiMessage.from(QUESTION));
        ChatModel model = model(compilation(false, "", true));
        PlanningRoute route = resumed.loop().selectRoute(model, TaskPlan.of(reply), SESSION, reply, null, history);

        PlanningLoop.RunOutcome result = run(resumed, model, reply, history, "second-execution", route);

        assertNotEquals(PlanningLoop.Outcome.FAILED, result.outcome(), result.answer());
        assertEquals("second-execution", resumed.store().get(SESSION).orElseThrow().executionId());
        assertNotNull(resumed.store().get(SESSION).orElseThrow().graph().byId("ui"));
        ArgumentCaptor<ChatRequest> request = ArgumentCaptor.forClass(ChatRequest.class);
        verify(model).chat(request.capture());
        assertTrue(payload(request.getValue()).contains(REQUEST + "\n" + reply));
        assertTrue(payload(request.getValue()).contains(QUESTION));
        verifyNoInteractions(first.executor(), resumed.executor());
    }

    @Test
    void invalidClarificationReplyPreservesTheWaitingSnapshotAndCannotDispatchTools() {
        Fixture fixture = fixture(properties(), new PlannerStateStore());
        assertEquals(PlanningLoop.Outcome.BLOCKED_ON_HUMAN, run(fixture,
                model(compilation(true, QUESTION, false)), REQUEST, List.of(), "first-execution", initialRoute()).outcome());
        StateSnapshot waiting = fixture.store().get(SESSION).orElseThrow();

        PlanningLoop.RunOutcome rejected = run(fixture, model(compilation(false, "", false)),
                "十二项功能是多模态记录、时间同步和版本对比", List.of(UserMessage.from(REQUEST), AiMessage.from(QUESTION)),
                "rejected-execution", PlanningRoute.dag("state", "CLARIFICATION_REPLY"));

        assertEquals(PlanningLoop.Outcome.FAILED, rejected.outcome());
        assertEquals("PLAN_VALIDATION_FAILED", rejected.reason());
        assertEquals(waiting, fixture.store().get(SESSION).orElseThrow());
        verifyNoInteractions(fixture.executor());
    }

    @Test
    void clarificationFlagCannotMakeEmptyOrMixedExecutableGraphsPassValidation() {
        GoalCompiler.CompileResult waiting = new GoalCompiler(properties()).compile(
                model(compilation(true, QUESTION, false)), REQUEST, plan(REQUEST));
        TaskNode executable = new TaskNode("rogue", "执行查询", "web", List.of(), List.of(), List.of("结果"),
                TaskNodeStatus.PENDING, 50, DoneWhen.note(), "", "", 0, "");
        for (TaskGraph graph : List.of(new TaskGraph(List.of()),
                new TaskGraph(List.of(waiting.graph().nodes().getFirst(), executable)))) {
            assertFalse(new PlanValidator().validate(graph, plan(REQUEST), waiting.goal(), waiting.intent()).valid());
            assertFalse(new IntentGraphValidator().validate(waiting.intent(), graph).valid());
        }
    }

    private static String compilation(boolean needsClarification, String question, boolean executableNodes) {
        var root = PlannerStateJson.MAPPER.createObjectNode();
        root.put("taskType", "代码生成").put("objective", REQUEST);
        root.putArray("constraints").add(CONSTRAINT);
        root.putArray("successCriteria").add("index.html");
        root.putObject("entities");
        var intent = root.putObject("intent");
        intent.put("objective", REQUEST).put("needsClarification", needsClarification);
        if (question == null) intent.putNull("clarificationQuestion");
        else intent.put("clarificationQuestion", question);
        intent.putArray("deliverables").addObject().put("name", "系统前端").put("type", "file")
                .put("path", "index.html").put("required", true);
        intent.putArray("inputs").addObject().put("name", "十二项功能").put("source", "用户消息").put("required", true);
        intent.putArray("constraints").add(CONSTRAINT);
        intent.putArray("exclusions");
        intent.putArray("acceptanceCriteria").add("index.html");
        var nodes = root.putArray("nodes");
        if (executableNodes) {
            var ui = nodes.addObject().put("id", "ui").put("name", "静态系统前端").put("capability", "code");
            ui.putArray("dependsOn");
            ui.putArray("inputs").add("十二项功能");
            ui.putArray("outputs").add("index.html");
            ui.putObject("doneWhen").put("type", "file_exists").put("path", "index.html")
                    .putArray("checks").add(CONSTRAINT);
            var browser = nodes.addObject().put("id", "verify-ui").put("name", "页面交互验收").put("capability", "browser");
            browser.putArray("dependsOn").add("ui");
            browser.putArray("inputs").add("index.html");
            browser.putArray("outputs").add("界面验收结果");
            browser.putObject("doneWhen").put("type", "note_required");
        }
        return root.toString();
    }

    private static PlannerProperties properties() {
        PlannerProperties properties = new PlannerProperties();
        properties.setCompilerRetry(0);
        properties.setMaxReplanRetries(0);
        properties.setMaxOuterRounds(3);
        return properties;
    }

    private static TaskPlan plan(String request) {
        return TaskPlan.of(request).withArtifactDeliveryRequested(true);
    }

    private static PlanningRoute initialRoute() {
        return new PlanningRoute(true, "model", "实现并验收静态系统", true);
    }

    private static ChatModel model(String json) {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder().aiMessage(AiMessage.from(json)).build());
        return model;
    }

    private static Fixture fixture(PlannerProperties properties, PlannerStateStore store) {
        NodeExecutor executor = mock(NodeExecutor.class);
        return new Fixture(new PlanningLoop(properties, new GoalCompiler(properties), new PlanValidator(), store,
                mock(GraphScheduler.class), mock(ToolRouter.class), mock(StepEvaluator.class), mock(RecoveryEngine.class),
                mock(TodoStateProjector.class), executor, mock(PlannerMetrics.class), mock(SessionLock.class),
                mock(ToolSuccessStats.class)), store, executor);
    }

    private static PlanningLoop.RunOutcome run(Fixture fixture, ChatModel model, String request,
                                               List<ChatMessage> history, String executionId, PlanningRoute route) {
        return fixture.loop().runWithOutcome(model, "system", request, null, history, TaskPlan.of(request),
                SESSION, "fence", executionId, null, route);
    }

    private static String payload(ChatRequest request) {
        UserMessage user = (UserMessage) request.messages().getLast();
        return user.contents().stream().filter(TextContent.class::isInstance).map(TextContent.class::cast)
                .map(TextContent::text).collect(java.util.stream.Collectors.joining("\n"));
    }

    private record Fixture(PlanningLoop loop, PlannerStateStore store, NodeExecutor executor) {}
}
