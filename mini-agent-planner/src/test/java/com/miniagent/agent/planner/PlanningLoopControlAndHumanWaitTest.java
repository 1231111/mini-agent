package com.miniagent.agent.planner;

import com.miniagent.agent.core.AgentLoop;
import com.miniagent.agent.core.ExecutionControl;
import com.miniagent.agent.core.NodeExecutor;
import com.miniagent.agent.execution.ToolInvocation;
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
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlanningLoopControlAndHumanWaitTest {
    @AfterEach
    void clearPermission() {
        PermissionContext.clear();
    }

    @Test
    void lateOldWorkerCannotSettleOrAdoptAReplacementTaskWithTheSameNodeIds() {
        Fixture f = fixture();
        when(f.executor().execute(any(), anyString(), anyString(), any(), anyList(), anyInt(), any(), any()))
                .thenAnswer(invocation -> {
                    StateSnapshot old = f.store().get("session").orElseThrow();
                    f.store().init("session", "replacement-execution", old.goal(), new TaskGraph(List.of(
                            node("lookup", "新任务读取", List.of(), "new-output"),
                            node("answer", "新任务回答", List.of("lookup"), "new-answer"))));
                    return success("旧任务的迟到结果");
                });

        PlanningLoop.RunOutcome outcome = run(f.loop(), "继续");

        assertEquals(PlanningLoop.Outcome.FAILED, outcome.outcome());
        assertEquals("STALE_EXECUTION", outcome.reason());
        StateSnapshot replacement = f.store().get("session").orElseThrow();
        assertEquals("replacement-execution", replacement.executionId());
        assertEquals(TaskNodeStatus.PENDING, replacement.graph().byId("lookup").status());
        assertEquals("", replacement.graph().byId("lookup").output());
        verify(f.executor(), times(1)).execute(any(), anyString(), anyString(), any(), anyList(), anyInt(), any(), any());
        verifyNoInteractions(f.evaluator(), f.recovery());
    }

    @ParameterizedTest
    @ValueSource(strings = {"DEADLINE_EXCEEDED", "CANCELLED", "CANCELED", "RESOURCE_EXCEEDED",
            "RESOURCE_QUOTA_EXCEEDED", "TOOL_BUDGET_EXCEEDED", "TENANT_QUOTA_EXCEEDED"})
    void controlStopOutranksPreviousSuccessAndDoesNotDispatchSuccessorsOrRecovery(String reason) {
        Fixture f = fixture();
        ToolResult prior = ToolResult.success("上海今日晴，20度，来源：测试样本。");
        when(f.executor().execute(any(), anyString(), anyString(), any(), anyList(), anyInt(), any(), any()))
                .thenReturn(new AgentLoop.LoopOutcome("任务已停止：" + reason, reason,
                        List.of(ToolExecutionResultMessage.from("one", "web_search", prior.legacyText())),
                        List.of("web_search"), ToolErrorCode.NONE, prior));

        PlanningLoop.RunOutcome outcome = run(f.loop(), "继续");

        assertEquals(PlanningLoop.Outcome.FAILED, outcome.outcome());
        assertEquals(reason, outcome.reason());
        StateSnapshot stopped = f.store().get("session").orElseThrow();
        assertEquals(reason, stopped.execution().get("stopReason"));
        assertEquals(TaskNodeStatus.OUTCOME_UNKNOWN, stopped.graph().byId("lookup").status());
        assertEquals(ToolStatus.UNKNOWN, stopped.graph().byId("lookup").toolStatus());
        assertTrue(stopped.graph().byId("lookup").output().contains("上海"));
        assertEquals(TaskNodeStatus.PENDING, stopped.graph().byId("answer").status());
        assertFalse(stopped.graph().allTerminalSuccess());
        verify(f.executor(), times(1)).execute(any(), anyString(), anyString(), any(), anyList(), anyInt(), any(), any());
        verifyNoInteractions(f.evaluator(), f.recovery());

        PlanningLoop.RunOutcome continued = run(f.loop(), "继续");
        assertEquals(AgentLoop.OUTCOME_UNKNOWN, continued.reason());
        verify(f.executor(), times(1)).execute(any(), anyString(), anyString(), any(), anyList(), anyInt(), any(), any());
    }

    @Test
    void controlStopBeforeAnyToolLeavesTheNodePendingWithoutInventingUnknownSideEffects() {
        Fixture f = fixture();
        when(f.executor().execute(any(), anyString(), anyString(), any(), anyList(), anyInt(), any(), any()))
                .thenReturn(new AgentLoop.LoopOutcome("任务已超过执行时限", AgentLoop.DEADLINE_EXCEEDED,
                        List.of(ToolExecutionResultMessage.from("history", "http_post", "上一轮已提交")),
                        List.of(), ToolErrorCode.NONE));

        PlanningLoop.RunOutcome outcome = run(f.loop(), "继续");

        assertEquals(AgentLoop.DEADLINE_EXCEEDED, outcome.reason());
        TaskNode stopped = f.store().get("session").orElseThrow().graph().byId("lookup");
        assertEquals(TaskNodeStatus.PENDING, stopped.status());
        assertNull(stopped.toolStatus());
        verifyNoInteractions(f.evaluator(), f.recovery());
    }

    @Test
    void stopReasonSurvivesAnUnknownToolResultWithoutMakingThatNodeReplayable() {
        Fixture f = fixture();
        ToolResult unknown = ToolResult.unknown("提交是否生效尚未确认", null);
        when(f.executor().execute(any(), anyString(), anyString(), any(), anyList(), anyInt(), any(), any()))
                .thenReturn(new AgentLoop.LoopOutcome("任务已取消", AgentLoop.CANCELLED,
                        List.of(), List.of("http_post"), ToolErrorCode.OUTCOME_UNKNOWN, unknown));

        PlanningLoop.RunOutcome outcome = run(f.loop(), "继续");

        assertEquals(AgentLoop.CANCELLED, outcome.reason());
        assertEquals(TaskNodeStatus.OUTCOME_UNKNOWN, f.store().get("session").orElseThrow().graph().byId("lookup").status());
        verifyNoInteractions(f.evaluator(), f.recovery());
    }

    @ParameterizedTest
    @EnumSource(value = ExecutionControl.StopReason.class, mode = EnumSource.Mode.EXCLUDE, names = "NONE")
    void boundControlStopBeforeDispatchPreservesItsReasonWithoutLockingTheNode(ExecutionControl.StopReason reason) {
        Fixture f = boundFixture();
        when(f.executor().executeBoundInvocation(anyString(), anyString())).thenReturn(
                ToolInvocation.controlStop("read_file", ToolResult.failure(errorCode(reason),
                        "execution stopped before dispatch", false), reason));

        PlanningLoop.RunOutcome outcome = run(f.loop(), "继续");

        assertEquals(PlanningLoop.Outcome.FAILED, outcome.outcome());
        assertEquals(reason.name(), outcome.reason());
        StateSnapshot stopped = f.store().get("session").orElseThrow();
        assertEquals(reason.name(), stopped.execution().get("stopReason"));
        assertEquals(TaskNodeStatus.PENDING, stopped.graph().byId("lookup").status());
        assertNull(stopped.graph().byId("lookup").toolStatus());
        assertEquals(TaskNodeStatus.PENDING, stopped.graph().byId("answer").status());
        verify(f.executor()).executeBoundInvocation(eq("read_file"), anyString());
        verify(f.executor(), never()).execute(any(), anyString(), anyString(), any(), anyList(), anyInt(), any(), any());
        verifyNoInteractions(f.evaluator(), f.recovery());
    }

    @ParameterizedTest
    @EnumSource(value = ExecutionControl.StopReason.class, mode = EnumSource.Mode.EXCLUDE, names = "NONE")
    void boundControlStopAfterDispatchRetainsReasonAndQuarantinesUnknownEffect(ExecutionControl.StopReason reason) {
        Fixture f = boundFixture();
        when(f.executor().executeBoundInvocation(anyString(), anyString())).thenReturn(
                ToolInvocation.controlStop("read_file", ToolResult.unknown("remote effect unconfirmed", null), reason));

        PlanningLoop.RunOutcome outcome = run(f.loop(), "继续");

        assertEquals(reason.name(), outcome.reason());
        assertEquals(TaskNodeStatus.OUTCOME_UNKNOWN,
                f.store().get("session").orElseThrow().graph().byId("lookup").status());
        assertEquals(AgentLoop.OUTCOME_UNKNOWN, run(f.loop(), "继续").reason());
        verify(f.executor(), times(1)).executeBoundInvocation(anyString(), anyString());
        verifyNoInteractions(f.evaluator(), f.recovery());
    }

    @ParameterizedTest
    @EnumSource(value = ToolErrorCode.class, names = {"TIMEOUT", "RATE_LIMITED", "CANCELLED"})
    void ordinaryBoundToolFailureDoesNotBecomeAGlobalExecutionStop(ToolErrorCode code) {
        Fixture f = boundFixture();
        when(f.executor().executeBoundInvocation(anyString(), anyString())).thenReturn(
                ToolInvocation.executed("read_file", ToolResult.failure(code, "upstream tool failed", true)));

        PlanningLoop.RunOutcome outcome = run(f.loop(), "继续");

        assertFalse(outcome.goalSatisfied());
        assertFalse(f.store().get("session").orElseThrow().execution().containsKey("stopReason"));
        verify(f.recovery(), atLeastOnce()).diagnose(any(), anyString(), anyString(), eq(code));
    }

    @ParameterizedTest
    @ValueSource(strings = {"USER_QUESTION", "PERM_ASK"})
    void boundHumanWaitPersistsAndAShortReplyStillPassesThroughTheToolApprovalBoundary(String reason) {
        Fixture f = boundFixture();
        ToolResult pending = ToolResult.failure(ToolErrorCode.PERMISSION_DENIED, "请授权读取", false);
        ToolInvocation invocation = AgentLoop.PERM_ASK.equals(reason)
                ? ToolInvocation.permissionAsk("read_file", pending)
                : ToolInvocation.userQuestion("read_file", pending, "请选择需要读取的文件");
        when(f.executor().executeBoundInvocation(anyString(), anyString())).thenReturn(invocation);

        PlanningLoop.RunOutcome first = run(f.loop(), "继续");
        assertEquals(PlanningLoop.Outcome.BLOCKED_ON_HUMAN, first.outcome());
        assertEquals(reason, first.reason());
        StateSnapshot waiting = f.store().get("session").orElseThrow();
        assertEquals(TaskNodeStatus.AWAITING_CONFIRM, waiting.graph().byId("lookup").status());
        assertTrue(waiting.execution().containsKey(PlanningLoop.EXECUTION_HUMAN_WAIT));

        PlanningLoop.RunOutcome second = run(f.loop(), "好的");

        assertEquals(PlanningLoop.Outcome.BLOCKED_ON_HUMAN, second.outcome());
        assertEquals(reason, second.reason());
        assertEquals(TaskNodeStatus.AWAITING_CONFIRM,
                f.store().get("session").orElseThrow().graph().byId("lookup").status());
        verify(f.executor(), times(2)).executeBoundInvocation(eq("read_file"), anyString());
        verify(f.executor(), never()).execute(any(), anyString(), anyString(), any(), anyList(), anyInt(), any(), any());
        verifyNoInteractions(f.evaluator(), f.recovery());
    }

    private static ToolErrorCode errorCode(ExecutionControl.StopReason reason) {
        return switch (reason) {
            case CANCELLED -> ToolErrorCode.CANCELLED;
            case DEADLINE_EXCEEDED -> ToolErrorCode.TIMEOUT;
            default -> ToolErrorCode.RATE_LIMITED;
        };
    }

    @ParameterizedTest
    @ValueSource(strings = {"USER_QUESTION", "PERM_ASK"})
    void humanWaitSurvivesSerializationAndShortReplyResumesTheOriginalGraph(String reason) throws Exception {
        Fixture first = fixture();
        when(first.executor().execute(any(), anyString(), anyString(), any(), anyList(), anyInt(), any(), any()))
                .thenReturn(new AgentLoop.LoopOutcome("请选择出发城市", reason, List.of(),
                        List.of("ask_user_question"), ToolErrorCode.NONE));

        PlanningLoop.RunOutcome waiting = run(first.loop(), "继续");

        assertEquals(PlanningLoop.Outcome.BLOCKED_ON_HUMAN, waiting.outcome());
        assertEquals(reason, waiting.reason());
        StateSnapshot persisted = first.store().get("session").orElseThrow();
        assertEquals(TaskNodeStatus.AWAITING_CONFIRM, persisted.graph().byId("lookup").status());
        assertTrue(persisted.graph().hasAwaitingConfirm());
        assertTrue(persisted.execution().containsKey(PlanningLoop.EXECUTION_HUMAN_WAIT));
        verifyNoInteractions(first.evaluator(), first.recovery());

        // Rebuild both the state store and PlanningLoop, as after a process restart.
        StateSnapshot restored = PlannerStateJson.MAPPER.readValue(
                PlannerStateJson.MAPPER.writeValueAsString(persisted), StateSnapshot.class);
        Fixture resumed = fixture(restored);
        when(resumed.executor().execute(any(), anyString(), anyString(), any(), anyList(), anyInt(), any(), any()))
                .thenReturn(success("已取得上海天气：晴，20度。"), success("上海今日晴，出行无需雨具。"));
        PlanningRoute route = resumed.loop().selectRoute(null, TaskPlan.of("上海"), "session", "上海", null, List.of());

        assertEquals(PlanningRoute.Strategy.DAG, route.strategy());
        assertEquals("RESUME_CURRENT_GRAPH", route.reason());
        PlanningLoop.RunOutcome completed = resumed.loop().runWithOutcome(null, "system", "上海", null, List.of(),
                TaskPlan.of("上海"), "session", "fence", "new-execution", null, route);

        assertTrue(completed.goalSatisfied(), completed.answer());
        StateSnapshot finalState = resumed.store().get("session").orElseThrow();
        assertEquals(persisted.goal(), finalState.goal());
        assertEquals(persisted.executionId(), finalState.executionId());
        assertEquals(List.of("lookup", "answer"), finalState.graph().nodes().stream().map(TaskNode::id).toList());
        assertFalse(finalState.execution().containsKey(PlanningLoop.EXECUTION_HUMAN_WAIT));
        verifyNoInteractions(resumed.compiler(), resumed.recovery());
        verify(resumed.executor(), times(2)).execute(any(), anyString(), anyString(), any(), anyList(), anyInt(), any(), any());
        verify(resumed.executor()).execute(any(), anyString(), eq("上海"), any(), anyList(), anyInt(), any(), any());
    }

    @Test
    void ordinaryTodoConfirmationIsNotConsumedAsAnAgentQuestionReply() {
        Fixture f = fixture();
        StateSnapshot original = f.store().get("session").orElseThrow();
        f.store().commit("session", original.version(), original.withGraph(original.graph().replace(
                original.graph().byId("lookup").withStatus(TaskNodeStatus.AWAITING_CONFIRM))));

        PlanningLoop.RunOutcome waiting = run(f.loop(), "上海");

        assertEquals(PlanningLoop.Outcome.BLOCKED_ON_HUMAN, waiting.outcome());
        assertEquals(TaskNodeStatus.AWAITING_CONFIRM, f.store().get("session").orElseThrow().graph().byId("lookup").status());
        verifyNoInteractions(f.executor(), f.evaluator(), f.recovery(), f.compiler());
    }

    private static AgentLoop.LoopOutcome success(String evidence) {
        ToolResult result = ToolResult.success(evidence);
        return new AgentLoop.LoopOutcome(evidence, "SUCCESS",
                List.of(ToolExecutionResultMessage.from("one", "web_search", result.legacyText())),
                List.of("web_search"), ToolErrorCode.NONE, result);
    }

    private static PlanningLoop.RunOutcome run(PlanningLoop loop, String reply) {
        return loop.runWithOutcome(null, "system", reply, null, List.of(), TaskPlan.of("查询天气并给出建议"),
                "session", "fence", "execution", null, PlanningRoute.dag("state", "RESUME_CURRENT_GRAPH"));
    }

    private static Fixture fixture() {
        return fixture(null);
    }

    private static Fixture boundFixture() {
        Fixture fixture = fixture();
        StateSnapshot snapshot = fixture.store().get("session").orElseThrow();
        TaskNode bound = new TaskNode("lookup", "读取 config.json", "file_read", List.of(),
                List.of(), List.of("config"), TaskNodeStatus.PENDING, 1, DoneWhen.note(), "", "", 0, "")
                .withToolArguments(Map.of("path", "config.json"));
        fixture.store().commit("session", snapshot.version(), snapshot.revisePlan(
                new TaskGraph(List.of(bound, node("answer", "解释配置", List.of("lookup"), "answer"))),
                snapshot.goal(), "fixture_bound_plan"));
        return fixture;
    }

    private static Fixture fixture(StateSnapshot restored) {
        PlannerProperties properties = new PlannerProperties();
        properties.setMaxOuterRounds(5);
        properties.setMaxRecoveries(0);
        PlannerStateStore store = new PlannerStateStore();
        if (restored == null) {
            Goal goal = new Goal("goal", "查询天气并给出建议", "", Map.of(), List.of(), List.of());
            store.init("session", "original-execution", goal, new TaskGraph(List.of(
                    node("lookup", "天气信息", List.of(), "weather"),
                    node("answer", "出行建议", List.of("lookup"), "advice"))));
        } else {
            StateSnapshot base = store.init("session", restored.executionId(), restored.goal(), restored.graph(),
                    restored.intent(), restored.compilationSource(), restored.degraded());
            store.commit("session", base.version(), base.withExecution(restored.execution()));
        }
        NodeExecutor executor = mock(NodeExecutor.class);
        GoalCompiler compiler = mock(GoalCompiler.class);
        CapabilityRegistry capabilities = mock(CapabilityRegistry.class);
        when(capabilities.toolsFor(anyString())).thenReturn(List.of("web_search", "ask_user_question", "read_file", "write_file"));
        SessionLock lock = mock(SessionLock.class);
        when(lock.renewSessionLock(anyString(), anyString())).thenReturn(true);
        PlannerMetrics metrics = new PlannerMetrics();
        StepEvaluator evaluator = spy(new StepEvaluator(properties, metrics, null));
        RecoveryEngine recovery = spy(new RecoveryEngine(store, capabilities, properties));
        PlanningLoop loop = new PlanningLoop(properties, compiler, new PlanValidator(), store,
                new GraphScheduler(properties), new ToolRouter(capabilities), evaluator, recovery,
                mock(TodoStateProjector.class), executor, metrics, lock, new ToolSuccessStats());
        return new Fixture(loop, store, executor, compiler, evaluator, recovery);
    }

    private static TaskNode node(String id, String name, List<String> dependencies, String output) {
        return new TaskNode(id, name, "web", dependencies, List.of(), List.of(output), TaskNodeStatus.PENDING,
                1, DoneWhen.note(), "", "", 0, "");
    }

    private record Fixture(PlanningLoop loop, PlannerStateStore store, NodeExecutor executor,
                           GoalCompiler compiler, StepEvaluator evaluator, RecoveryEngine recovery) {}
}
