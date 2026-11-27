package com.miniagent.agent.planner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.miniagent.agent.core.AgentLoop;
import com.miniagent.agent.core.LoopTurnContext;
import com.miniagent.agent.core.NodeExecutor;
import com.miniagent.agent.permission.PermissionContext;
import com.miniagent.agent.task.TaskPlan;
import com.miniagent.agent.tool.CapabilityRegistry;
import com.miniagent.agent.tool.ToolErrorCode;
import com.miniagent.agent.tool.ToolRegistry;
import com.miniagent.agent.tool.ToolResult;
import com.miniagent.agent.tool.ToolStatus;
import com.miniagent.agent.tool.WorkspaceContext;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PlanningLoopRecoveryContinuationTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path workspace;

    @AfterEach
    void clearContexts() {
        WorkspaceContext.clearAll();
        LoopTurnContext.clear();
        PermissionContext.clear();
    }

    @Test
    void graphAcceptanceFailureWithoutFeedbackIsReanalyzedAfterUserContinuesInsteadOfReplaying() throws Exception {
        String session = "s_graph_acceptance_continuation";
        String execution = "exec_graph_acceptance_continuation";
        Files.writeString(workspace.resolve("input.txt"), "verified source content\n");
        PlannerProperties properties = new PlannerProperties();
        PlannerStateStore store = new PlannerStateStore();
        Goal goal = goal();
        TaskNode inspect = node("inspect", List.of("facts"), DoneWhen.note(), TaskNodeStatus.PENDING);
        StateSnapshot initial = store.init(session, execution, goal, new TaskGraph(List.of(inspect)),
                TaskIntent.fromGoal(goal));
        GoalCompiler compiler = mock(GoalCompiler.class);
        ChatModel chat = mock(ChatModel.class);
        when(compiler.resolvePlannerChat(chat)).thenReturn(chat);
        AtomicInteger analyses = new AtomicInteger();
        when(chat.chat(any(ChatRequest.class))).thenAnswer(invocation -> {
            ChatRequest request = invocation.getArgument(0);
            JsonNode payload = JSON.readTree(((UserMessage) request.messages().get(1)).singleText());
            assertThat(payload.path("failure").path("kind").asText()).isEqualTo("EVAL_FAILED");
            assertThat(payload.path("acceptanceFeedback").isEmpty()).isTrue();
            assertThat(payload.path("identity").path("nodeId").asText()).isEqualTo("inspect");
            analyses.incrementAndGet();
            JsonNode identity = payload.path("identity");
            ObjectNode response = JSON.createObjectNode().put("schemaVersion", 1);
            for (String key : List.of("sessionId", "executionId", "planVersion", "nodeId")) {
                response.set(key, identity.get(key));
            }
            response.put("action", "WAIT_FOR_USER").put("rootCause", "Need a reliable way to inspect the final state");
            response.putArray("nextSteps");
            response.putArray("evidenceNeeded").add("independent current-state observation");
            ChatResponse result = mock(ChatResponse.class);
            when(result.aiMessage()).thenReturn(AiMessage.from(response.toString()));
            return result;
        });
        AtomicInteger dispatches = new AtomicInteger();
        NodeExecutor executor = mock(NodeExecutor.class);
        doAnswer(invocation -> {
            dispatches.incrementAndGet();
            String arguments = "{\"path\":\"input.txt\"}";
            assertThat(LoopTurnContext.current().denyToolInvocation("read_file", arguments)).isNull();
            ToolResult result = ToolResult.success(Files.readString(workspace.resolve("input.txt")));
            LoopTurnContext.current().observeToolOutcome("read_file", arguments, result);
            List<ChatMessage> messages = List.of(AiMessage.from(ToolExecutionRequest.builder()
                            .id("read-source").name("read_file").arguments(arguments).build()),
                    ToolExecutionResultMessage.from("read-source", "read_file", result.legacyText()));
            return new AgentLoop.LoopOutcome("source inspected", "SUCCESS", messages,
                    List.of("read_file"), ToolErrorCode.NONE, result);
        }).when(executor).execute(any(ChatModel.class), anyString(), anyString(), any(), anyList(),
                anyInt(), any(TaskPlan.class), any());
        StepEvaluator evaluator = spy(new StepEvaluator(properties, new PlannerMetrics(), null));
        // GraphEval has no AcceptanceFeedback field: this reproduces that separate failure path.
        doReturn(StepEvaluator.GraphEval.fail("inspect", "final state observation is not trustworthy"))
                .when(evaluator).evaluateGraph(any(Goal.class), any(TaskGraph.class), any(StateSnapshot.class));
        PlanningLoop loop = loop(properties, store, compiler, evaluator, executor);

        PlanningLoop.RunOutcome first;
        try (RecoveryBudget.Scope ignored = RecoveryBudget.bindRound("first-user", true)) {
            first = run(loop, chat, goal, session, execution);
        }
        StateSnapshot waiting = store.get(session).orElseThrow();
        assertThat(first.outcome()).isEqualTo(PlanningLoop.Outcome.BLOCKED_ON_HUMAN);
        assertThat(first.reason()).isEqualTo("RECOVERY_ANALYSIS_BLOCKED");
        assertThat(waiting.graph().byId("inspect").status()).isEqualTo(TaskNodeStatus.AWAITING_CONFIRM);
        assertThat(waiting.execution()).containsKey(RecoveryRequirement.KEY)
                .doesNotContainKey(PlanningLoop.ACCEPTANCE_FAILURES);
        assertThat(RecoveryRequirement.diagnosis(waiting, waiting.graph().byId("inspect"))).isNotNull();

        PlanningLoop.RunOutcome second;
        try (RecoveryBudget.Scope ignored = RecoveryBudget.bindRound("second-user", true)) {
            second = run(loop, chat, goal, session, execution);
        }
        StateSnapshot stillWaiting = store.get(session).orElseThrow();
        assertThat(second.outcome()).isEqualTo(PlanningLoop.Outcome.BLOCKED_ON_HUMAN);
        assertThat(second.reason()).isEqualTo("RECOVERY_ANALYSIS_BLOCKED");
        assertThat(analyses).hasValue(2);
        assertThat(dispatches).hasValue(1);
        assertThat(stillWaiting.planVersion()).isEqualTo(initial.planVersion());
        assertThat(stillWaiting.graph().byId("inspect").status()).isEqualTo(TaskNodeStatus.AWAITING_CONFIRM);
        verify(chat, times(2)).chat(any(ChatRequest.class));
        verify(evaluator).evaluateGraph(any(Goal.class), any(TaskGraph.class), any(StateSnapshot.class));
        verify(compiler, never()).compileWithCorrection(any(ChatModel.class), anyString(), any(TaskPlan.class),
                anyString(), anyList(), any(TaskGraph.class));
    }

    @Test
    void toolBudgetReplanCannotRemoveFrozenChecksEvenWithoutASemanticDiagnosis() {
        assertToolBudgetReplanRejected(true);
    }

    @Test
    void toolBudgetReplanCannotRemoveNamedOutputsEvenWithoutASemanticDiagnosis() {
        assertToolBudgetReplanRejected(false);
    }

    private void assertToolBudgetReplanRejected(boolean removeCheck) {
        String session = "s_budget_contract_" + removeCheck;
        String execution = "exec_budget_contract_" + removeCheck;
        PlannerProperties properties = new PlannerProperties();
        properties.setProposalMaxToolCalls(1);
        properties.setMaxReplanRetries(0);
        properties.setMaxOuterRounds(0);
        PlannerStateStore store = new PlannerStateStore();
        Goal goal = goal();
        DoneWhen frozen = new DoneWhen(DoneWhen.NOTE, "来源必须真实可核验", "",
                List.of("搜索回执与读取区域匹配", "只读原文件"));
        TaskNode exhausted = node("inspect", List.of("facts", "source-proof"), frozen, TaskNodeStatus.RUNNING);
        StateSnapshot initial = store.init(session, execution, goal, new TaskGraph(List.of(exhausted)),
                TaskIntent.fromGoal(goal));
        assertThat(store.reserveNodeToolCall(session, execution, initial.planVersion(), "inspect", 1).reserved())
                .isTrue();
        StateSnapshot spent = store.get(session).orElseThrow();
        StateSnapshot before = store.commit(session, spent.version(), spent.withGraph(spent.graph().replace(
                spent.graph().byId("inspect").withStatus(TaskNodeStatus.FAILED).withError("节点工具预算耗尽"))));
        TaskNode invalid = node("focused_inspect", removeCheck ? exhausted.outputs() : List.of("facts"),
                removeCheck ? new DoneWhen(frozen.type(), frozen.criteria(), frozen.path(), List.of("只读原文件"))
                        : frozen, TaskNodeStatus.PENDING);
        GoalCompiler compiler = mock(GoalCompiler.class);
        ChatModel chat = mock(ChatModel.class);
        when(compiler.compileWithCorrection(any(ChatModel.class), anyString(), any(TaskPlan.class),
                anyString(), anyList(), any(TaskGraph.class))).thenReturn(new GoalCompiler.CompileResult(
                goal, new TaskGraph(List.of(invalid)), false, GoalCompiler.CompilationSource.MODEL, "",
                TaskIntent.fromGoal(goal)));
        NodeExecutor executor = mock(NodeExecutor.class);
        PlanningLoop loop = loop(properties, store, compiler,
                new StepEvaluator(properties, new PlannerMetrics(), null), executor);

        PlanningLoop.RunOutcome result;
        try (RecoveryBudget.Scope ignored = RecoveryBudget.bindRound("new-budget-user", true)) {
            result = run(loop, chat, goal, session, execution);
        }

        StateSnapshot retained = store.get(session).orElseThrow();
        assertThat(result.outcome()).isEqualTo(PlanningLoop.Outcome.FAILED);
        assertThat(result.reason()).isEqualTo("REPLAN_VALIDATION_FAILED");
        assertThat(retained.planVersion()).isEqualTo(before.planVersion());
        assertThat(retained.graph()).isEqualTo(before.graph());
        assertThat(retained.goal()).isEqualTo(goal);
        assertThat(retained.intent()).isEqualTo(before.intent());
        assertThat(store.events(session)).noneMatch(event -> event.type() == DomainEventType.RECOVERY_APPLIED);
        verify(compiler).compileWithCorrection(any(ChatModel.class), anyString(), any(TaskPlan.class),
                anyString(), anyList(), any(TaskGraph.class));
        verifyNoInteractions(chat, executor);
    }

    private static PlanningLoop loop(PlannerProperties properties, PlannerStateStore store,
                                     GoalCompiler compiler, StepEvaluator evaluator, NodeExecutor executor) {
        ToolRouter router = mock(ToolRouter.class);
        when(router.allowedFor(any(ActionProposal.class), any(TaskGraph.class), anyBoolean()))
                .thenReturn(List.of("read_file", "search_code", "todo"));
        SessionLock lock = mock(SessionLock.class);
        when(lock.renewSessionLock(anyString(), anyString())).thenReturn(true);
        PlanValidator validator = mock(PlanValidator.class);
        when(validator.validate(any(TaskGraph.class), any(TaskPlan.class), any(Goal.class), any(TaskIntent.class)))
                .thenReturn(new PlanValidationReport(List.of(), List.of()));
        return new PlanningLoop(properties, compiler, validator, store, new GraphScheduler(properties), router,
                evaluator, new RecoveryEngine(store, new CapabilityRegistry(new ToolRegistry()), properties),
                mock(TodoStateProjector.class), executor, new PlannerMetrics(), lock, mock(ToolSuccessStats.class));
    }

    private static PlanningLoop.RunOutcome run(PlanningLoop loop, ChatModel chat, Goal goal,
                                               String session, String execution) {
        return loop.runWithOutcome(chat, "", "继续核验源文件事实", null, List.of(), TaskPlan.of(goal.objective()),
                session, "fence", execution, null, PlanningRoute.resume("test", "RESUME_EXISTING"));
    }

    private static Goal goal() {
        return new Goal("continuation-goal", "核验源文件事实", "", "research", Map.of(), List.of(), List.of());
    }

    private static TaskNode node(String id, List<String> outputs, DoneWhen doneWhen, TaskNodeStatus status) {
        return new TaskNode(id, "核验源文件事实", "research", List.of(), List.of(), outputs,
                status, 90, doneWhen, "", "", 0, "");
    }
}
