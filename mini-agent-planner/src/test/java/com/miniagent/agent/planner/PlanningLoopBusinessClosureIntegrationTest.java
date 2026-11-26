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

        TaskNode replacementNode = new TaskNode("artifact_focused", "核验目标页面文件是否满足要求",
                "research", List.of(), List.of(), List.of("deliverable.html"), TaskNodeStatus.PENDING,
                90, DoneWhen.file("deliverable.html"), "", "", 0, "");
        Goal goal = goal("核验页面文件");
        GoalCompiler.CompileResult replacement = new GoalCompiler.CompileResult(goal,
                new TaskGraph(List.of(replacementNode)), false,
                GoalCompiler.CompilationSource.MODEL, "", TaskIntent.fromGoal(goal));
        when(compiler.resolvePlannerChat(chat)).thenReturn(chat);
        ChatResponse routeResponse = mock(ChatResponse.class);
        when(routeResponse.aiMessage()).thenReturn(AiMessage.from("""
                {"route":"dag","reason":"继续同一未完成任务","taskBoundary":"SAME",
                 "dagAction":"RESUME_EXISTING","artifactDelivery":{"requested":false,"evidence":""}}
                """));
        when(chat.chat(any(ChatRequest.class))).thenReturn(routeResponse);
        when(compiler.compileWithCorrection(any(ChatModel.class), anyString(), any(TaskPlan.class),
                anyString(), anyList(), any(TaskGraph.class))).thenReturn(replacement);
        properties.setMaxOuterRounds(0); // exercise recovery admission without executing the replacement node
        PlanningRoute continuedRoute = loop.selectRoute(chat, taskPlan, sessionId,
                "重试当前未完成任务", null, List.of());
        assertThat(continuedRoute.dagAction()).isEqualTo(PlanningRoute.DagAction.RESUME_EXISTING);
        PlanningLoop.RunOutcome continued = loop.runWithOutcome(chat, "", "重试当前未完成任务", null,
                List.of(), taskPlan, sessionId, "fence", executionId, null,
                continuedRoute);
        StateSnapshot afterContinue = stateStore.get(sessionId).orElseThrow();

        assertThat(continued.outcome()).isEqualTo(PlanningLoop.Outcome.UNFINISHED);
        assertThat(afterContinue.planVersion()).isGreaterThan(afterFirst.planVersion());
        assertThat(afterContinue.graph().byId("artifact")).isNull();
        TaskNode revised = afterContinue.graph().nodes().stream()
                .filter(candidate -> candidate.name().equals(replacementNode.name())).findFirst().orElseThrow();
        assertThat(revised.id()).startsWith("rp");
        assertThat(executedTools).hasValue(1); // the old node is never dispatched after its persisted budget is spent
        assertThat(stateStore.events(sessionId)).filteredOn(event ->
                event.type() == DomainEventType.NODE_TOOL_CALL_RESERVED).hasSize(1);
        assertThat(stateStore.events(sessionId)).anySatisfy(event -> {
            assertThat(event.type()).isEqualTo(DomainEventType.NODE_TOOL_BUDGET_RECOVERY_RESERVED);
            assertThat(event.taskId()).isEqualTo("artifact");
        });
        verify(compiler).compileWithCorrection(any(ChatModel.class), anyString(), any(TaskPlan.class),
                anyString(), anyList(), any(TaskGraph.class));
    }

    @Test
    void explicitResumeReplansAnExhaustedNodeOnceAndPreservesAcceptedReceipts() {
        String sessionId = "s_planner_budget_replan";
        String executionId = "exec_planner_budget_replan";
        String objective = "核验输入并完成页面";
        Goal goal = goal(objective);
        TaskIntent intent = TaskIntent.fromGoal(goal);
        PlannerStateStore stateStore = new PlannerStateStore();
        TaskNode accepted = new TaskNode("accepted", "已核验验收说明", "research", List.of(),
                List.of(), List.of("acceptance-understood"), TaskNodeStatus.SUCCESS,
                90, DoneWhen.note(), "", "", 0, "验收说明已核验");
        TaskNode exhausted = new TaskNode("inspect", "梳理大型输入文件", "research", List.of(),
                List.of(), List.of("game-logic"), TaskNodeStatus.RUNNING,
                80, DoneWhen.note(), "", "", 0, "");
        TaskNode pending = new TaskNode("implement", "实现页面改造", "code", List.of("inspect"),
                List.of("game-logic"), List.of("page.html"), TaskNodeStatus.PENDING,
                70, DoneWhen.file("page.html"), "", "", 0, "");
        StateSnapshot initial = initState(stateStore, sessionId, executionId, objective,
                new TaskGraph(List.of(accepted, exhausted, pending)), intent);
        StateSnapshot running = initial.withGraph(initial.graph().replace(
                initial.graph().byId("inspect").withStatus(TaskNodeStatus.RUNNING)));
        running = stateStore.commit(sessionId, initial.version(), running);
        assertThat(stateStore.reserveNodeToolCall(sessionId, executionId, running.planVersion(),
                "inspect", 1).reserved()).isTrue();
        StateSnapshot spent = stateStore.get(sessionId).orElseThrow();
        TaskGraph failedGraph = spent.graph().replace(spent.graph().byId("inspect")
                .withStatus(TaskNodeStatus.FAILED).withError("节点工具预算已耗尽"));
        spent = stateStore.commit(sessionId, spent.version(), spent.withGraph(failedGraph));

        TaskNode replacementNode = new TaskNode("inspect_focused", "使用已确认路径提取关键逻辑", "research",
                List.of(), List.of(), List.of("focused-game-logic"), TaskNodeStatus.PENDING,
                90, DoneWhen.note(), "", "", 0, "");
        GoalCompiler.CompileResult replacement = new GoalCompiler.CompileResult(goal,
                new TaskGraph(List.of(replacementNode)), false,
                GoalCompiler.CompilationSource.MODEL, "", intent);
        GoalCompiler compiler = mock(GoalCompiler.class);
        when(compiler.compileWithCorrection(any(ChatModel.class), anyString(), any(TaskPlan.class),
                anyString(), anyList(), any(TaskGraph.class))).thenReturn(replacement);
        RecoveryEngine recovery = mock(RecoveryEngine.class);
        when(recovery.classCount(any(StateSnapshot.class), eq(FailureClass.REWRITE_GRAPH))).thenReturn(0);
        when(recovery.classLimit(FailureClass.REWRITE_GRAPH)).thenReturn(2);
        PlannerProperties properties = new PlannerProperties();
        properties.setProposalMaxToolCalls(1);
        properties.setMaxRecoveries(0); // user-authorized recovery has its own durable bounded allowance
        properties.setMaxOuterRounds(0); // verify admission/replanning before any replacement tool dispatch
        NodeExecutor executor = mock(NodeExecutor.class);
        PlanningLoop loop = planningLoop(properties, stateStore, executor, List.of("read_file"),
                compiler, recovery);

        PlanningLoop.RunOutcome outcome = loop.runWithOutcome(mock(ChatModel.class), "",
                "继续完成页面改造", null, List.of(), TaskPlan.of(objective), sessionId,
                "fence", executionId, null, PlanningRoute.resume("model", "RESUME_EXISTING"));

        StateSnapshot revised = stateStore.get(sessionId).orElseThrow();
        assertThat(outcome.outcome()).isEqualTo(PlanningLoop.Outcome.UNFINISHED);
        assertThat(revised.planVersion()).isGreaterThan(spent.planVersion());
        assertThat(revised.graph().byId("accepted")).isEqualTo(accepted);
        assertThat(revised.graph().byId("inspect")).isNull();
        TaskNode replacementInGraph = revised.graph().nodes().stream()
                .filter(node -> node.name().equals(replacementNode.name())).findFirst().orElseThrow();
        assertThat(replacementInGraph.id()).startsWith("rp");
        assertThat(stateStore.nodeToolCallsUsed(revised, replacementInGraph.id())).isZero();
        assertThat(stateStore.events(sessionId)).anySatisfy(event -> {
            assertThat(event.type()).isEqualTo(DomainEventType.NODE_TOOL_BUDGET_RECOVERY_RESERVED);
            assertThat(event.taskId()).isEqualTo("inspect");
            assertThat(event.payload()).containsEntry("callsUsed", 1)
                    .containsEntry("maxCalls", 1).containsEntry("attemptsUsed", 1)
                    .containsEntry("taskAttemptsUsed", 1).containsEntry("maxTaskAttempts", 3);
        });
        assertThat(stateStore.events(sessionId)).anySatisfy(event -> {
            if (event.type() == DomainEventType.RECOVERY_APPLIED) {
                assertThat(event.payload()).containsEntry("explicitUserResume", true);
            }
        });
        verify(compiler).compileWithCorrection(any(ChatModel.class), anyString(), any(TaskPlan.class),
                anyString(), anyList(), any(TaskGraph.class));
        verifyNoInteractions(executor);
    }

    @Test
    void knownFailedTraceSessionResumesAtTheTwelveCallBoundaryAndReplansOnlyThatNode() {
        String sessionId = "s_e4802c49c8c641628bf3fea87f6a6fce";
        String executionId = "exec_trace_e4802c49_budget_resume";
        String objective = "继续将单机游戏改造成局域网联机版并完成双客户端验收";
        Goal goal = goal(objective);
        TaskIntent intent = TaskIntent.fromGoal(goal);
        PlannerStateStore stateStore = new PlannerStateStore();
        TaskNode accepted = new TaskNode("game_facts", "已提取原游戏状态管理事实", "research", List.of(),
                List.of(), List.of("game-state-facts"), TaskNodeStatus.SUCCESS, 95,
                DoneWhen.note(), "", "", 0, "已确认游戏状态由 index.html 管理");
        TaskNode exhausted = new TaskNode("acceptance", "整理联机改造验收要求", "research", List.of(),
                List.of("game-state-facts"), List.of("acceptance-requirements"), TaskNodeStatus.RUNNING,
                90, DoneWhen.note(), "", "", 0, "");
        StateSnapshot snapshot = initState(stateStore, sessionId, executionId, objective,
                new TaskGraph(List.of(accepted, exhausted)), intent);
        for (int call = 0; call < 12; call++) {
            PlannerStateStore.NodeToolCallReservation reservation = stateStore.reserveNodeToolCall(
                    sessionId, executionId, snapshot.planVersion(), "acceptance", 12);
            assertThat(reservation.reserved()).isTrue();
            snapshot = stateStore.get(sessionId).orElseThrow();
        }
        PlannerStateStore.NodeToolCallReservation denied = stateStore.reserveNodeToolCall(
                sessionId, executionId, snapshot.planVersion(), "acceptance", 12);
        assertThat(denied.reserved()).isFalse();
        assertThat(denied.callsUsed()).isEqualTo(12);
        TaskGraph failed = snapshot.graph().replace(snapshot.graph().byId("acceptance")
                .withStatus(TaskNodeStatus.FAILED).withError(denied.reason()));
        snapshot = stateStore.commit(sessionId, snapshot.version(), snapshot.withGraph(failed));

        TaskNode replacementNode = new TaskNode("focused_acceptance", "基于既有游戏事实整理联机验收清单",
                "research", List.of("game_facts"), List.of("game-state-facts"), List.of("multiplayer-acceptance"),
                TaskNodeStatus.PENDING, 95, DoneWhen.note(), "", "", 0, "");
        GoalCompiler.CompileResult replacement = new GoalCompiler.CompileResult(goal,
                new TaskGraph(List.of(replacementNode)), false,
                GoalCompiler.CompilationSource.MODEL, "", intent);
        GoalCompiler compiler = mock(GoalCompiler.class);
        RecoveryEngine recovery = mock(RecoveryEngine.class);
        when(compiler.resolvePlannerChat(any(ChatModel.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(compiler.compileWithCorrection(any(ChatModel.class), anyString(), any(TaskPlan.class),
                anyString(), anyList(), any(TaskGraph.class))).thenReturn(replacement);
        when(recovery.classCount(any(StateSnapshot.class), eq(FailureClass.REWRITE_GRAPH))).thenReturn(99);
        when(recovery.classLimit(FailureClass.REWRITE_GRAPH)).thenReturn(0);
        PlannerProperties properties = new PlannerProperties();
        properties.setProposalMaxToolCalls(12);
        properties.setMaxRecoveries(0);
        properties.setMaxOuterRounds(0);
        NodeExecutor executor = mock(NodeExecutor.class);
        PlanningLoop loop = planningLoop(properties, stateStore, executor, List.of("read_file"),
                compiler, recovery);
        ChatModel chat = mock(ChatModel.class);
        ChatResponse routeResponse = mock(ChatResponse.class);
        when(routeResponse.aiMessage()).thenReturn(AiMessage.from("""
                {"route":"dag","reason":"继续同一未完成的联机改造","taskBoundary":"SAME",
                 "dagAction":"RESUME_EXISTING","artifactDelivery":{"requested":false,"evidence":""}}
                """));
        when(chat.chat(any(ChatRequest.class))).thenReturn(routeResponse);
        TaskPlan taskPlan = TaskPlan.of(objective);

        PlanningRoute route = loop.selectRoute(chat, taskPlan, sessionId, "继续完成联机改造",
                null, List.of());
        PlanningLoop.RunOutcome outcome = loop.runWithOutcome(chat, "", "继续完成联机改造",
                null, List.of(), taskPlan, sessionId, "fence", executionId, null, route);

        StateSnapshot revised = stateStore.get(sessionId).orElseThrow();
        assertThat(route.dagAction()).isEqualTo(PlanningRoute.DagAction.RESUME_EXISTING);
        assertThat(outcome.outcome()).isEqualTo(PlanningLoop.Outcome.UNFINISHED);
        assertThat(outcome.reason()).isNotEqualTo("TOOL_BUDGET_RECOVERY_EXHAUSTED");
        assertThat(revised.planVersion()).isGreaterThan(snapshot.planVersion());
        assertThat(revised.graph().byId("game_facts")).isEqualTo(accepted);
        assertThat(revised.graph().byId("acceptance")).isNull();
        assertThat(revised.graph().nodes()).anySatisfy(node -> {
            assertThat(node.name()).isEqualTo(replacementNode.name());
            assertThat(node.id()).startsWith("rp");
        });
        assertThat(revised.execution()).containsEntry(
                PlannerStateStore.EXECUTION_TOOL_BUDGET_RECOVERY_ATTEMPTS_TOTAL, 1);
        assertThat(stateStore.events(sessionId)).anySatisfy(event -> {
            assertThat(event.type()).isEqualTo(DomainEventType.NODE_TOOL_BUDGET_RECOVERY_RESERVED);
            assertThat(event.taskId()).isEqualTo("acceptance");
            assertThat(event.payload()).containsEntry("callsUsed", 12).containsEntry("maxCalls", 12);
        });
        verify(compiler).compileWithCorrection(any(ChatModel.class), anyString(), any(TaskPlan.class),
                anyString(), anyList(), any(TaskGraph.class));
        verifyNoInteractions(executor);
    }

    @Test
    void aReplacementNodeThatAlsoExhaustsBudgetGetsASecondBoundedRecovery() {
        String sessionId = "s_trace_replacement_budget_resume";
        String executionId = "exec_trace_replacement_budget_resume";
        String objective = "继续整理联机改造验收要求";
        Goal goal = goal(objective);
        TaskIntent intent = TaskIntent.fromGoal(goal);
        PlannerStateStore stateStore = new PlannerStateStore();
        TaskNode exhausted = new TaskNode("acceptance", "整理联机改造验收要求", "research", List.of(),
                List.of(), List.of("acceptance-requirements"), TaskNodeStatus.RUNNING, 90,
                DoneWhen.note(), "", "", 0, "");
        StateSnapshot initial = initState(stateStore, sessionId, executionId, objective,
                new TaskGraph(List.of(exhausted)), intent);
        assertThat(stateStore.reserveNodeToolCall(sessionId, executionId, initial.planVersion(),
                "acceptance", 1).reserved()).isTrue();
        StateSnapshot spent = stateStore.get(sessionId).orElseThrow();
        spent = stateStore.commit(sessionId, spent.version(), spent.withGraph(
                spent.graph().replace(spent.graph().byId("acceptance")
                        .withStatus(TaskNodeStatus.FAILED).withError("工具调用上限 1"))));

        TaskNode replacementOne = new TaskNode("acceptance_focused",
                "基于已有事实整理验收清单", "research", List.of(), List.of(),
                List.of("acceptance-requirements-v1"), TaskNodeStatus.PENDING, 90,
                DoneWhen.note(), "", "", 0, "");
        TaskNode replacementTwo = new TaskNode("acceptance_focused_v2",
                "基于已有事实补齐验收清单", "research", List.of(), List.of(),
                List.of("acceptance-requirements-v2"), TaskNodeStatus.PENDING, 90,
                DoneWhen.note(), "", "", 0, "");
        GoalCompiler compiler = mock(GoalCompiler.class);
        when(compiler.compileWithCorrection(any(ChatModel.class), anyString(), any(TaskPlan.class),
                anyString(), anyList(), any(TaskGraph.class)))
                .thenReturn(new GoalCompiler.CompileResult(goal, new TaskGraph(List.of(replacementOne)),
                        false, GoalCompiler.CompilationSource.MODEL, "", intent))
                .thenReturn(new GoalCompiler.CompileResult(goal, new TaskGraph(List.of(replacementTwo)),
                        false, GoalCompiler.CompilationSource.MODEL, "", intent));
        RecoveryEngine recovery = mock(RecoveryEngine.class);
        when(recovery.classCount(any(StateSnapshot.class), eq(FailureClass.REWRITE_GRAPH))).thenReturn(99);
        when(recovery.classLimit(FailureClass.REWRITE_GRAPH)).thenReturn(0);
        PlannerProperties properties = new PlannerProperties();
        properties.setProposalMaxToolCalls(1);
        properties.setMaxOuterRounds(0);
        properties.setMaxRecoveries(0);
        properties.setMaxToolBudgetRecoveryReplansPerNode(2);
        properties.setMaxToolBudgetRecoveryReplansPerTask(3);
        NodeExecutor executor = mock(NodeExecutor.class);
        PlanningLoop loop = planningLoop(properties, stateStore, executor, List.of("read_file"),
                compiler, recovery);
        TaskPlan plan = TaskPlan.of(objective);
        PlanningRoute route = PlanningRoute.resume("model", "RESUME_EXISTING");

        PlanningLoop.RunOutcome first = loop.runWithOutcome(mock(ChatModel.class), "", "继续",
                null, List.of(), plan, sessionId, "fence", executionId, null, route);
        StateSnapshot afterFirst = stateStore.get(sessionId).orElseThrow();
        TaskNode firstReplacement = afterFirst.graph().nodes().stream()
                .filter(node -> node.name().equals(replacementOne.name())).findFirst().orElseThrow();
        assertThat(first.outcome()).isEqualTo(PlanningLoop.Outcome.UNFINISHED);
        assertThat(afterFirst.execution()).containsEntry(
                PlannerStateStore.EXECUTION_TOOL_BUDGET_RECOVERY_ATTEMPTS_TOTAL, 1);

        StateSnapshot replacementRunning = stateStore.commit(sessionId, afterFirst.version(),
                afterFirst.withGraph(afterFirst.graph().replace(
                        firstReplacement.withStatus(TaskNodeStatus.RUNNING))));
        assertThat(stateStore.reserveNodeToolCall(sessionId, executionId, replacementRunning.planVersion(),
                firstReplacement.id(), 1).reserved()).isTrue();
        StateSnapshot replacementSpent = stateStore.get(sessionId).orElseThrow();
        replacementSpent = stateStore.commit(sessionId, replacementSpent.version(), replacementSpent.withGraph(
                replacementSpent.graph().replace(replacementSpent.graph().byId(firstReplacement.id())
                        .withStatus(TaskNodeStatus.FAILED).withError("工具调用上限 1"))));

        PlanningLoop.RunOutcome second = loop.runWithOutcome(mock(ChatModel.class), "", "继续",
                null, List.of(), plan, sessionId, "fence", executionId, null, route);
        StateSnapshot afterSecond = stateStore.get(sessionId).orElseThrow();
        assertThat(second.reason()).isNotEqualTo("NO_PROGRESS");
        assertThat(second.reason()).isNotEqualTo("TOOL_BUDGET_RECOVERY_EXHAUSTED");
        assertThat(afterSecond.planVersion()).isGreaterThan(afterFirst.planVersion());
        assertThat(afterSecond.graph().nodes()).anySatisfy(node ->
                assertThat(node.name()).isEqualTo(replacementTwo.name()));
        assertThat(afterSecond.execution()).containsEntry(
                PlannerStateStore.EXECUTION_TOOL_BUDGET_RECOVERY_ATTEMPTS_TOTAL, 2);
        assertThat(stateStore.events(sessionId)).filteredOn(event ->
                event.type() == DomainEventType.NODE_TOOL_BUDGET_RECOVERY_RESERVED).hasSize(2);
        verify(compiler, org.mockito.Mockito.times(2)).compileWithCorrection(any(ChatModel.class),
                anyString(), any(TaskPlan.class), anyString(), anyList(), any(TaskGraph.class));
        verifyNoInteractions(executor);
    }

    @Test
    void transientPlannerFailureCanUseOneMoreDurableResumeAttempt() {
        String sessionId = "s_planner_budget_transient_recovery";
        String executionId = "exec_planner_budget_transient_recovery";
        String objective = "核验输入并完成页面";
        Goal goal = goal(objective);
        TaskIntent intent = TaskIntent.fromGoal(goal);
        PlannerStateStore stateStore = new PlannerStateStore();
        TaskNode exhausted = new TaskNode("inspect", "梳理输入文件", "research", List.of(), List.of(),
                List.of("input-facts"), TaskNodeStatus.RUNNING, 90, DoneWhen.note(), "", "", 0, "");
        StateSnapshot initial = initState(stateStore, sessionId, executionId, objective,
                new TaskGraph(List.of(exhausted)), intent);
        assertThat(stateStore.reserveNodeToolCall(sessionId, executionId, initial.planVersion(),
                "inspect", 1).reserved()).isTrue();
        StateSnapshot spent = stateStore.get(sessionId).orElseThrow();
        spent = stateStore.commit(sessionId, spent.version(), spent.withGraph(spent.graph().replace(
                spent.graph().byId("inspect").withStatus(TaskNodeStatus.FAILED)
                        .withError("节点工具预算已耗尽"))));

        TaskNode replacementNode = new TaskNode("inspect_focused", "只读核验已有输入并提取关键事实",
                "research", List.of(), List.of(), List.of("verified-facts"), TaskNodeStatus.PENDING,
                90, DoneWhen.note(), "", "", 0, "");
        GoalCompiler.CompileResult replacement = new GoalCompiler.CompileResult(goal,
                new TaskGraph(List.of(replacementNode)), false,
                GoalCompiler.CompilationSource.MODEL, "", intent);
        GoalCompiler compiler = mock(GoalCompiler.class);
        when(compiler.compileWithCorrection(any(ChatModel.class), anyString(), any(TaskPlan.class),
                anyString(), anyList(), any(TaskGraph.class)))
                .thenThrow(new IllegalStateException("temporary planner timeout"))
                .thenReturn(replacement);
        RecoveryEngine recovery = mock(RecoveryEngine.class);
        when(recovery.classCount(any(StateSnapshot.class), eq(FailureClass.REWRITE_GRAPH))).thenReturn(100);
        when(recovery.classLimit(FailureClass.REWRITE_GRAPH)).thenReturn(0);
        PlannerProperties properties = new PlannerProperties();
        properties.setProposalMaxToolCalls(1);
        properties.setMaxRecoveries(0);
        properties.setMaxOuterRounds(0);
        NodeExecutor executor = mock(NodeExecutor.class);
        PlanningLoop loop = planningLoop(properties, stateStore, executor, List.of("read_file"),
                compiler, recovery);
        TaskPlan plan = TaskPlan.of(objective);

        PlanningLoop.RunOutcome first = loop.runWithOutcome(mock(ChatModel.class), "", "继续",
                null, List.of(), plan, sessionId, "fence", executionId, null,
                PlanningRoute.resume("model", "RESUME_EXISTING"));
        StateSnapshot afterTransientFailure = stateStore.get(sessionId).orElseThrow();
        PlanningLoop.RunOutcome second = loop.runWithOutcome(mock(ChatModel.class), "", "继续",
                null, List.of(), plan, sessionId, "fence", executionId, null,
                PlanningRoute.resume("model", "RESUME_EXISTING"));

        assertThat(first.reason()).isEqualTo("REPLAN_COMPILATION_FAILED");
        assertThat(afterTransientFailure.planVersion()).isEqualTo(spent.planVersion());
        assertThat(afterTransientFailure.execution())
                .containsEntry(PlannerStateStore.EXECUTION_TOOL_BUDGET_RECOVERY_ATTEMPTS_TOTAL, 1);
        assertThat(second.outcome()).isEqualTo(PlanningLoop.Outcome.UNFINISHED);
        assertThat(stateStore.get(sessionId).orElseThrow().planVersion()).isGreaterThan(spent.planVersion());
        assertThat(stateStore.get(sessionId).orElseThrow().execution())
                .containsEntry(PlannerStateStore.EXECUTION_TOOL_BUDGET_RECOVERY_ATTEMPTS_TOTAL, 2);
        verify(compiler, org.mockito.Mockito.times(2)).compileWithCorrection(any(ChatModel.class), anyString(),
                any(TaskPlan.class), anyString(), anyList(), any(TaskGraph.class));
        verifyNoInteractions(executor);
    }

    @Test
    void missingPlannerModelDoesNotConsumeTheDurableRecoveryAllowance() {
        String sessionId = "s_planner_budget_model_unavailable";
        String executionId = "exec_planner_budget_model_unavailable";
        String objective = "核验输入并完成页面";
        Goal goal = goal(objective);
        PlannerStateStore stateStore = new PlannerStateStore();
        TaskNode exhausted = new TaskNode("inspect", "梳理输入文件", "research", List.of(), List.of(),
                List.of("input-facts"), TaskNodeStatus.RUNNING, 90, DoneWhen.note(), "", "", 0, "");
        StateSnapshot initial = initState(stateStore, sessionId, executionId, objective,
                new TaskGraph(List.of(exhausted)), TaskIntent.fromGoal(goal));
        assertThat(stateStore.reserveNodeToolCall(sessionId, executionId, initial.planVersion(),
                "inspect", 1).reserved()).isTrue();
        StateSnapshot spent = stateStore.get(sessionId).orElseThrow();
        spent = stateStore.commit(sessionId, spent.version(), spent.withGraph(spent.graph().replace(
                spent.graph().byId("inspect").withStatus(TaskNodeStatus.FAILED))));
        assertThat(spent.graph().byId("inspect").status()).isEqualTo(TaskNodeStatus.FAILED);
        assertThat(spent.graph().byId("inspect").toolStatus()).isNull();

        GoalCompiler compiler = mock(GoalCompiler.class);
        NodeExecutor executor = mock(NodeExecutor.class);
        PlannerProperties properties = new PlannerProperties();
        properties.setProposalMaxToolCalls(1);
        PlanningLoop loop = planningLoop(properties, stateStore, executor, List.of("read_file"), compiler);

        PlanningLoop.RunOutcome outcome = loop.runWithOutcome(null, "", "继续",
                null, List.of(), TaskPlan.of(objective), sessionId, "fence", executionId, null,
                PlanningRoute.resume("model", "RESUME_EXISTING"));

        assertThat(outcome.reason()).isEqualTo("TOOL_BUDGET_RECOVERY_UNAVAILABLE");
        StateSnapshot after = stateStore.get(sessionId).orElseThrow();
        assertThat(after.execution()).doesNotContainKey(
                PlannerStateStore.EXECUTION_TOOL_BUDGET_RECOVERY_ATTEMPTS_TOTAL);
        assertThat(after.graph()).isEqualTo(spent.graph());
        verifyNoInteractions(compiler, executor);
    }

    @Test
    void rejectedBudgetRecoveryIsDurableAndDoesNotRecompileOnEveryResume() {
        String sessionId = "s_planner_budget_replan_rejected";
        String executionId = "exec_planner_budget_replan_rejected";
        String objective = "梳理并改造页面";
        Goal goal = goal(objective);
        TaskIntent intent = TaskIntent.fromGoal(goal);
        PlannerStateStore stateStore = new PlannerStateStore();
        TaskNode exhausted = new TaskNode("inspect", "梳理输入文件", "research", List.of(), List.of(),
                List.of("input-state"), TaskNodeStatus.RUNNING, 90, DoneWhen.note(), "", "", 0, "");
        StateSnapshot initial = initState(stateStore, sessionId, executionId, objective,
                new TaskGraph(List.of(exhausted)), intent);
        StateSnapshot running = stateStore.commit(sessionId, initial.version(), initial.withGraph(
                initial.graph().replace(initial.graph().byId("inspect").withStatus(TaskNodeStatus.RUNNING))));
        assertThat(stateStore.reserveNodeToolCall(sessionId, executionId, running.planVersion(),
                "inspect", 1).reserved()).isTrue();
        StateSnapshot spent = stateStore.get(sessionId).orElseThrow();
        spent = stateStore.commit(sessionId, spent.version(), spent.withGraph(spent.graph().replace(
                spent.graph().byId("inspect").withStatus(TaskNodeStatus.FAILED)
                        .withError("节点工具预算已耗尽"))));

        TaskNode invalid = new TaskNode("bad", "重复读取输入", "research", List.of(), List.of(), List.of(),
                TaskNodeStatus.PENDING, 90, DoneWhen.note(), "", "", 0, "");
        GoalCompiler compiler = mock(GoalCompiler.class);
        when(compiler.compileWithCorrection(any(ChatModel.class), anyString(), any(TaskPlan.class),
                anyString(), anyList(), any(TaskGraph.class))).thenReturn(new GoalCompiler.CompileResult(
                goal, new TaskGraph(List.of(invalid)), false, GoalCompiler.CompilationSource.MODEL, "", intent));
        PlanValidator validator = mock(PlanValidator.class);
        when(validator.validate(any(TaskGraph.class), any(TaskPlan.class), any(Goal.class), any(TaskIntent.class)))
                .thenReturn(new PlanValidationReport(List.of(new PlanValidationReport.ValidationError(
                        PlanValidationReport.ValidationError.Type.LOW_LEVEL_NODE_NAME, "bad",
                        "节点名称只描述内部操作", "收敛成业务验收步骤")), List.of()));
        RecoveryEngine recovery = mock(RecoveryEngine.class);
        when(recovery.classCount(any(StateSnapshot.class), eq(FailureClass.REWRITE_GRAPH))).thenReturn(0);
        when(recovery.classLimit(FailureClass.REWRITE_GRAPH)).thenReturn(2);
        PlannerProperties properties = new PlannerProperties();
        properties.setProposalMaxToolCalls(1);
        NodeExecutor executor = mock(NodeExecutor.class);
        PlanningLoop loop = planningLoop(properties, stateStore, executor, List.of("read_file"),
                compiler, recovery, validator);

        PlanningLoop.RunOutcome first = loop.runWithOutcome(mock(ChatModel.class), "", "继续",
                null, List.of(), TaskPlan.of(objective), sessionId, "fence", executionId,
                null, PlanningRoute.resume("model", "RESUME_EXISTING"));
        StateSnapshot afterRejectedReplan = stateStore.get(sessionId).orElseThrow();
        int compileCallsAfterFirstResume = org.mockito.Mockito.mockingDetails(compiler)
                .getInvocations().size();
        PlanningLoop.RunOutcome second = loop.runWithOutcome(mock(ChatModel.class), "", "继续",
                null, List.of(), TaskPlan.of(objective), sessionId, "fence", executionId,
                null, PlanningRoute.resume("model", "RESUME_EXISTING"));
        int compileCallsAfterSecondResume = org.mockito.Mockito.mockingDetails(compiler)
                .getInvocations().size();
        PlanningLoop.RunOutcome third = loop.runWithOutcome(mock(ChatModel.class), "", "继续",
                null, List.of(), TaskPlan.of(objective), sessionId, "fence", executionId,
                null, PlanningRoute.resume("model", "RESUME_EXISTING"));

        assertThat(first.reason()).isEqualTo("REPLAN_VALIDATION_FAILED");
        assertThat(second.reason()).isEqualTo("REPLAN_VALIDATION_FAILED");
        assertThat(third.reason()).isEqualTo("TOOL_BUDGET_RECOVERY_EXHAUSTED");
        assertThat(afterRejectedReplan.planVersion()).isEqualTo(spent.planVersion());
        assertThat(afterRejectedReplan.graph()).isEqualTo(spent.graph());
        assertThat(compileCallsAfterFirstResume).isPositive();
        assertThat(compileCallsAfterSecondResume).isGreaterThan(compileCallsAfterFirstResume);
        assertThat(org.mockito.Mockito.mockingDetails(compiler).getInvocations())
                .hasSize(compileCallsAfterSecondResume);
        assertThat(stateStore.get(sessionId).orElseThrow().execution())
                .containsEntry(PlannerStateStore.EXECUTION_TOOL_BUDGET_RECOVERY_ATTEMPTS_TOTAL, 2);
        verifyNoInteractions(executor);
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
                new TaskGraph(List.of(unknown.withStatus(TaskNodeStatus.RUNNING)
                        .withToolStatus(com.miniagent.agent.tool.ToolStatus.UNKNOWN))),
                        TaskIntent.fromGoal(goal));

        assertThat(stateStore.reserveNodeToolCall(sessionId, executionId, before.planVersion(),
                "server", 1).reserved()).isTrue();
        StateSnapshot spent = stateStore.get(sessionId).orElseThrow();
        StateSnapshot withUnknownSideEffect = stateStore.commit(sessionId, spent.version(),
                spent.withGraph(spent.graph().replace(spent.graph().byId("server")
                        .withStatus(TaskNodeStatus.FAILED)
                        .withToolStatus(com.miniagent.agent.tool.ToolStatus.UNKNOWN))));

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
        StateSnapshot stopped = stateStore.get(sessionId).orElseThrow();
        assertThat(stopped.planVersion()).isEqualTo(withUnknownSideEffect.planVersion());
        assertThat(stopped.graph().byId("server").status()).isEqualTo(TaskNodeStatus.OUTCOME_UNKNOWN);
        assertThat(stopped.graph().byId("server").toolStatus())
                .isEqualTo(com.miniagent.agent.tool.ToolStatus.UNKNOWN);
        assertThat(stopped.execution().get(PlannerStateStore.EXECUTION_TOOL_BUDGET_RECOVERY_ATTEMPTS_TOTAL))
                .isNull();
        assertThat(stateStore.events(sessionId)).noneMatch(event ->
                event.type() == DomainEventType.NODE_TOOL_BUDGET_RECOVERY_RESERVED);
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
        return planningLoop(properties, stateStore, executor, allowedTools, compiler,
                mock(RecoveryEngine.class), mock(PlanValidator.class));
    }

    private static PlanningLoop planningLoop(PlannerProperties properties,
                                             PlannerStateStore stateStore,
                                             NodeExecutor executor,
                                             List<String> allowedTools,
                                             GoalCompiler compiler,
                                             RecoveryEngine recovery) {
        return planningLoop(properties, stateStore, executor, allowedTools, compiler,
                recovery, mock(PlanValidator.class));
    }

    private static PlanningLoop planningLoop(PlannerProperties properties,
                                             PlannerStateStore stateStore,
                                             NodeExecutor executor,
                                             List<String> allowedTools,
                                             GoalCompiler compiler,
                                             RecoveryEngine recovery,
                                             PlanValidator validator) {
        ToolRouter router = mock(ToolRouter.class);
        when(router.allowedFor(any(ActionProposal.class), any(TaskGraph.class), anyBoolean()))
                .thenReturn(allowedTools);
        SessionLock lock = mock(SessionLock.class);
        when(lock.renewSessionLock(anyString(), anyString())).thenReturn(true);
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
