package com.miniagent.agent.planner;

import com.miniagent.agent.core.AgentLoop;
import com.miniagent.agent.core.AgentStreamSink;
import com.miniagent.agent.core.LoopTurnContext;
import com.miniagent.agent.core.NodeExecutor;
import com.miniagent.agent.execution.ToolInvocation;
import com.miniagent.agent.permission.PermissionContext;
import com.miniagent.agent.task.TaskPlan;
import com.miniagent.agent.tool.ToolErrorCode;
import com.miniagent.agent.tool.ToolResult;
import com.miniagent.agent.todo.HumanYield;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Exercises Planner orchestration and task state through Java APIs, without driving the UI. */
class PlanningLoopBusinessClosureIntegrationTest {

    @TempDir
    Path workspace;

    @AfterEach
    void clearThreadContexts() {
        WorkspaceContext.clearAll();
        LoopTurnContext.clear();
        PermissionContext.clear();
    }

    @Test
    void noProgressBudgetSpansNodeContinuationAndPersistsFailureEvidence() {
        String sessionId = "s_planner_no_progress_e2e";
        String executionId = "exec_planner_no_progress_e2e";
        PlannerStateStore stateStore = new PlannerStateStore();
        TaskGraph graph = new TaskGraph(List.of(noteNode("research")));
        StateSnapshot initial = initState(stateStore, sessionId, executionId, "核验业务事实", graph,
                TaskIntent.fromGoal(goal("核验业务事实")));

        PlannerProperties properties = new PlannerProperties();
        properties.setProposalMaxToolCalls(2);
        properties.setProposalMaxChunks(6);
        AtomicInteger dispatches = new AtomicInteger();
        AtomicInteger executedTools = new AtomicInteger();
        NodeExecutor executor = mock(NodeExecutor.class);
        doAnswer(invocation -> simulateChunk(invocation, dispatches, executedTools))
                .when(executor).execute(any(ChatModel.class), anyString(), anyString(), any(), anyList(),
                        anyInt(), any(TaskPlan.class), any());
        doAnswer(invocation -> simulateChunk(invocation, dispatches, executedTools))
                .when(executor).continueNode(any(ChatModel.class), anyList(), anyString(), anyInt(),
                        any(TaskPlan.class), any());

        PlanningLoop loop = planningLoop(properties, stateStore, executor, List.of("read_file"));
        PlanningLoop.RunOutcome outcome = loop.runWithOutcome(mock(ChatModel.class), "", "继续", null,
                List.of(), TaskPlan.of("核验业务事实"), sessionId, "fence", executionId, null,
                PlanningRoute.resume("test", "RESUME_CURRENT_GRAPH"));

        StateSnapshot persisted = stateStore.get(sessionId).orElseThrow();
        TaskNode failed = persisted.graph().byId("research");
        assertThat(outcome.outcome()).isEqualTo(PlanningLoop.Outcome.UNFINISHED);
        assertThat(outcome.reason()).isEqualTo(AgentLoop.NO_PROGRESS);
        assertThat(dispatches).hasValue(3); // two chunks' work, then a denied third dispatch
        assertThat(executedTools).hasValue(2);
        assertThat(failed.status()).isEqualTo(TaskNodeStatus.FAILED);
        assertThat(failed.lastError()).contains("工具调用上限 2");
        assertThat(stateStore.events(sessionId)).filteredOn(event ->
                event.type() == DomainEventType.NODE_TOOL_CALL_RESERVED)
                .hasSize(2);
        verify(executor, never()).executeBoundInvocation(anyString(), anyString());
        assertThat(initial.planVersion()).isEqualTo(persisted.planVersion());
    }

    @Test
    void nodeToolBudgetSurvivesAnExplicitContinueAcrossPlanningLoopRuns() {
        String sessionId = "s_planner_continue_budget_e2e";
        String executionId = "exec_planner_continue_budget_e2e";
        PlannerStateStore stateStore = new PlannerStateStore();
        TaskNode node = new TaskNode("artifact", "核验页面文件", "research", List.of(), List.of(),
                List.of("deliverable.html"), TaskNodeStatus.PENDING, 90,
                DoneWhen.file("deliverable.html"), "", Map.of(), "", List.of(), "", 0, "");
        TaskGraph graph = new TaskGraph(List.of(node));
        initState(stateStore, sessionId, executionId, "核验页面文件", graph,
                TaskIntent.fromGoal(goal("核验页面文件")));

        PlannerProperties properties = new PlannerProperties();
        properties.setProposalMaxToolCalls(1);
        properties.setProposalMaxChunks(1);
        AtomicInteger dispatches = new AtomicInteger();
        AtomicInteger executedTools = new AtomicInteger();
        NodeExecutor executor = mock(NodeExecutor.class);
        doAnswer(invocation -> simulateChunk(invocation, dispatches, executedTools))
                .when(executor).execute(any(ChatModel.class), anyString(), anyString(), any(), anyList(),
                        anyInt(), any(TaskPlan.class), any());
        doAnswer(invocation -> simulateChunk(invocation, dispatches, executedTools))
                .when(executor).continueNode(any(ChatModel.class), anyList(), anyString(), anyInt(),
                        any(TaskPlan.class), any());
        GoalCompiler compiler = mock(GoalCompiler.class);
        PlanningLoop loop = planningLoop(properties, stateStore, executor, List.of("read_file"), compiler);
        ChatModel chat = mock(ChatModel.class);
        TaskPlan taskPlan = TaskPlan.of("核验页面文件");

        PlanningLoop.RunOutcome first = loop.runWithOutcome(chat, "", "继续", null,
                List.of(), taskPlan, sessionId, "fence", executionId, null,
                PlanningRoute.resume("test", "RESUME_EXISTING_GRAPH"));
        StateSnapshot afterFirst = stateStore.get(sessionId).orElseThrow();
        assertThat(first.outcome()).isEqualTo(PlanningLoop.Outcome.UNFINISHED);
        assertThat(afterFirst.graph().byId("artifact").status()).isEqualTo(TaskNodeStatus.READY);
        assertThat(executedTools).hasValue(1);

        PlanningLoop.RunOutcome continued = loop.runWithOutcome(chat, "", "重试当前未完成任务", null,
                List.of(), taskPlan, sessionId, "fence", executionId, null,
                PlanningRoute.resume("test", "RESUME_CURRENT_GRAPH"));
        StateSnapshot afterContinue = stateStore.get(sessionId).orElseThrow();

        assertThat(continued.outcome()).isEqualTo(PlanningLoop.Outcome.UNFINISHED);
        assertThat(continued.reason()).isEqualTo(AgentLoop.NO_PROGRESS);
        assertThat(afterContinue.graph().byId("artifact").status()).isEqualTo(TaskNodeStatus.FAILED);
        assertThat(afterContinue.graph().byId("artifact").lastError()).contains("工具调用上限 1");
        assertThat(executedTools).hasValue(1); // the second run is stopped before the handler
        assertThat(stateStore.events(sessionId)).filteredOn(event ->
                event.type() == DomainEventType.NODE_TOOL_CALL_RESERVED).hasSize(1);
        verifyNoInteractions(compiler);
    }

    @Test
    void semanticResumeDoesNotReplayAnUnknownExternalOutcome() {
        String sessionId = "s_planner_resume_unknown_outcome";
        String executionId = "exec_planner_resume_unknown_outcome";
        PlannerStateStore stateStore = new PlannerStateStore();
        Goal goal = goal("继续完成联机改造");
        TaskNode unknown = new TaskNode("server", "启动联机服务并核验房间", "shell",
                List.of(), List.of(), List.of("server-process"), TaskNodeStatus.OUTCOME_UNKNOWN,
                90, DoneWhen.note(), "", "unknown side effect", 0, "")
                .withToolStatus(com.miniagent.agent.tool.ToolStatus.UNKNOWN);
        StateSnapshot before = initState(stateStore, sessionId, executionId, goal.objective(),
                new TaskGraph(List.of(unknown)), TaskIntent.fromGoal(goal));

        GoalCompiler compiler = mock(GoalCompiler.class);
        NodeExecutor executor = mock(NodeExecutor.class);
        PlanningLoop loop = planningLoop(new PlannerProperties(), stateStore, executor,
                List.of("shell"), compiler);

        PlanningLoop.RunOutcome outcome = loop.runWithOutcome(mock(ChatModel.class), "",
                "重试当前未完成任务", null, List.of(), TaskPlan.of(goal.objective()),
                sessionId, "fence", executionId, null,
                PlanningRoute.resume("model", "RESUME_EXISTING"));

        assertThat(outcome.outcome()).isEqualTo(PlanningLoop.Outcome.FAILED);
        assertThat(outcome.reason()).isEqualTo("OUTCOME_UNKNOWN");
        assertThat(stateStore.get(sessionId).orElseThrow()).isEqualTo(before);
        verifyNoInteractions(compiler, executor);
    }

    @Test
    void declaredHtmlArtifactIsWrittenAndPassesTaskScopedIndependentReviewAndGraphAcceptance()
            throws Exception {
        String sessionId = "s_planner_artifact_e2e";
        String executionId = "exec_planner_artifact_e2e";
        WorkspaceContext.setRootOverride(workspace);
        String html = "<!doctype html><html><body><main>ready</main></body></html>\n";
        TaskNode write = new TaskNode("page", "生成页面文件", "file_write", List.of(), List.of(),
                List.of("deliverable.html"), TaskNodeStatus.PENDING, 90,
                DoneWhen.file("deliverable.html"), "",
                Map.of("path", "deliverable.html", "content", html), "", List.of(), "", 0, "");
        TaskGraph graph = new TaskGraph(List.of(write));
        Goal goal = goal("制作可运行的页面");
        TaskIntent intent = new TaskIntent(goal.objective(),
                List.of(new TaskIntent.Deliverable("页面", "html", "deliverable.html", true)),
                List.of(), List.of(), List.of(), List.of(), false, "");
        PlannerStateStore stateStore = new PlannerStateStore();
        initState(stateStore, sessionId, executionId, goal.objective(), graph, intent);

        ChatModel reviewer = mock(ChatModel.class);
        ChatResponse reviewResponse = mock(ChatResponse.class);
        when(reviewResponse.aiMessage()).thenReturn(AiMessage.from(
                "{\"decision\":\"PASS\",\"findings\":[]}"));
        when(reviewer.chat(any(ChatRequest.class))).thenReturn(reviewResponse);
        CodeDiffReviewService reviewService = new CodeDiffReviewService(null, null);

        NodeExecutor executor = mock(NodeExecutor.class);
        doAnswer(invocation -> {
            String tool = invocation.getArgument(0);
            String arguments = invocation.getArgument(1);
            assertThat(tool).isEqualTo("write_file");
            String scopeDenial = LoopTurnContext.current().denyToolInvocation(tool, arguments);
            assertThat(scopeDenial).isNull();
            Files.writeString(workspace.resolve("deliverable.html"), html);
            return ToolInvocation.executed(tool, ToolResult.success(
                    "{\"success\":true,\"path\":\"deliverable.html\"}"));
        }).when(executor).executeBoundInvocation(anyString(), anyString());

        PlanningLoop loop = planningLoop(new PlannerProperties(), stateStore, executor, List.of("write_file"));
        ReflectionTestUtils.setField(loop, "codeDiffReviewService", reviewService);

        PlanningLoop.RunOutcome outcome = loop.runWithOutcome(reviewer, "", "继续", null,
                List.of(), TaskPlan.of(goal.objective()), sessionId, "fence", executionId, null,
                PlanningRoute.resume("test", "RESUME_CURRENT_GRAPH"));

        assertThat(Files.readString(workspace.resolve("deliverable.html"))).isEqualTo(html);
        assertThat(outcome.outcome()).isEqualTo(PlanningLoop.Outcome.COMPLETED);
        assertThat(stateStore.get(sessionId).orElseThrow().graph().byId("page").status())
                .isEqualTo(TaskNodeStatus.SUCCESS);
        verify(reviewer).chat(any(ChatRequest.class));
        verify(executor).executeBoundInvocation(eq("write_file"), anyString());
    }

    private static Object simulateChunk(org.mockito.invocation.InvocationOnMock invocation,
                                        AtomicInteger dispatches, AtomicInteger executedTools) {
        ProposalTurnPolicy policy = (ProposalTurnPolicy) LoopTurnContext.current();
        int dispatch = dispatches.incrementAndGet();
        String arguments = "{\"path\":\"evidence-" + dispatch + ".txt\"}";
        String denial = policy.denyToolInvocation("read_file", arguments);
        if (denial != null) {
            return new AgentLoop.LoopOutcome(policy.stopReason(), AgentLoop.NO_PROGRESS,
                    List.of(), List.of("read_file"), ToolErrorCode.PERMISSION_DENIED, null);
        }
        executedTools.incrementAndGet();
        ToolResult receipt = ToolResult.success("observation-" + dispatch);
        policy.observeToolOutcome("read_file", arguments, receipt);
        String id = "read-" + dispatch;
        List<ChatMessage> messages = List.of(
                AiMessage.builder().text("").toolExecutionRequests(List.of(ToolExecutionRequest.builder()
                        .id(id).name("read_file").arguments(arguments).build())).build(),
                ToolExecutionResultMessage.from(id, "read_file", receipt.legacyText()));
        return new AgentLoop.LoopOutcome("继续核验", AgentLoop.LOOP_MAX_ITERATIONS,
                messages, List.of("read_file"), ToolErrorCode.NONE, receipt);
    }

    private static PlanningLoop planningLoop(PlannerProperties properties,
                                             PlannerStateStore stateStore,
                                             NodeExecutor executor,
                                             List<String> allowedTools) {
        return planningLoop(properties, stateStore, executor, allowedTools, mock(GoalCompiler.class));
    }

    private static PlanningLoop planningLoop(PlannerProperties properties,
                                             PlannerStateStore stateStore,
                                             NodeExecutor executor,
                                             List<String> allowedTools,
                                             GoalCompiler compiler) {
        ToolRouter router = mock(ToolRouter.class);
        when(router.allowedFor(any(ActionProposal.class), any(TaskGraph.class), anyBoolean()))
                .thenReturn(allowedTools);
        SessionLock lock = mock(SessionLock.class);
        when(lock.renewSessionLock(anyString(), anyString())).thenReturn(true);
        PlanValidator validator = mock(PlanValidator.class);
        RecoveryEngine recovery = mock(RecoveryEngine.class);
        return new PlanningLoop(properties, compiler, validator, stateStore,
                new GraphScheduler(properties), router, new StepEvaluator(properties, new PlannerMetrics(), null),
                recovery, mock(TodoStateProjector.class), executor, new PlannerMetrics(), lock,
                mock(ToolSuccessStats.class));
    }

    private static StateSnapshot initState(PlannerStateStore store, String sessionId, String executionId,
                                           String objective, TaskGraph graph, TaskIntent intent) {
        return store.init(sessionId, executionId, goal(objective), graph, intent,
                GoalCompiler.CompilationSource.MODEL, false);
    }

    private static TaskNode noteNode(String id) {
        return new TaskNode(id, "核验业务事实", "research", List.of(), List.of(), List.of(),
                TaskNodeStatus.PENDING, 90, DoneWhen.note(), "", Map.of(), "", List.of(), "", 0, "");
    }

    private static Goal goal(String objective) {
        return new Goal("goal-" + objective.hashCode(), objective, "", "code",
                Map.of(), List.of(), List.of());
    }
}
