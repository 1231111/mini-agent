package com.miniagent.agent.planner;

import com.miniagent.agent.core.NodeExecutor;
import com.miniagent.agent.permission.PermissionContext;
import com.miniagent.agent.permission.PermissionMode;
import com.miniagent.agent.task.TaskPlan;
import com.miniagent.agent.trace.TraceRecorder;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** A selected DAG must be validated before any execution path can dispatch tools. */
class PlanningLoopInvalidPlanTest {
    @AfterEach
    void clearPermission() {
        PermissionContext.clear();
    }

    @ParameterizedTest
    @EnumSource(value = PermissionMode.class, names = {"DEFAULT", "PLAN"})
    void invalidNewDagCannotFallBackToDirectExecution(PermissionMode mode) {
        Fixture f = fixture();
        PermissionContext.force("s", mode, true);

        PlanningLoop.RunOutcome result = invoke(f, "比较两份资料并交付结论", null);

        assertPlanFailure(f, result);
        assertTrue(f.store().get("s").isEmpty(), "a rejected graph must not become resumable state");
        verify(f.compiler()).compile(isNull(), eq("比较两份资料并交付结论"), any());
        verifyNoInteractions(f.executor(), f.scheduler(), f.projector());
    }

    @Test
    void invalidClarificationRecompilePreservesOriginalSnapshotAndDoesNotExecute() {
        Fixture f = fixture();
        Goal original = new Goal("clarify", "比较指定来源后给出结论", "", Goal.TASK_TYPE_CLARIFY,
                Map.of(), List.of(), List.of());
        StateSnapshot waiting = f.store().init("s", "original-execution", original, GoalCompiler.clarifyGraph());
        waiting = f.store().commit("s", waiting.version(), waiting.withExecution(Map.of(
                "artifactDeliveryRequested", false)));
        String clarification = "来源是 https://example.com/a 和 https://example.com/b";

        PlanningLoop.RunOutcome result = invoke(f, clarification, null);

        assertPlanFailure(f, result);
        assertEquals(waiting, f.store().get("s").orElseThrow());
        var plan = org.mockito.ArgumentCaptor.forClass(TaskPlan.class);
        verify(f.compiler()).compile(isNull(), eq(original.objective() + "\n" + clarification), plan.capture());
        assertEquals(original.objective() + "\n" + clarification, plan.getValue().taskGoal());
        assertEquals(Boolean.FALSE, plan.getValue().artifactDeliveryRequested());
        verifyNoInteractions(f.executor(), f.scheduler(), f.projector());
    }

    @Test
    void invalidMultimodalDagCannotBypassValidationThroughMultimodalDirectExecution() {
        Fixture f = fixture();
        String request = "按照参考图片实现页面并在浏览器中验收";
        UserMessage multimodal = UserMessage.from(List.of(TextContent.from(request),
                ImageContent.from("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jWZkAAAAASUVORK5CYII=",
                        "image/png")));

        PlanningLoop.RunOutcome result = invoke(f, request, multimodal);

        assertPlanFailure(f, result);
        assertTrue(f.store().get("s").isEmpty());
        verify(f.compiler()).compile(isNull(), eq(request), any());
        verifyNoInteractions(f.executor(), f.scheduler(), f.projector());
    }

    @Test
    void unresolvedRoutingIsRecordedAsFailureInsteadOfASuccessfulChoice() {
        Fixture f = fixture();

        PlanningRoute route = f.loop().selectRoute(null, TaskPlan.of("比较资料"), "s", "比较资料", null, List.of());

        assertFalse(route.resolved());
        verify(f.trace()).recordNode(eq("s"), eq(0), eq("PLANNING_ROUTE"),
                contains("\"route\":\"unresolved\""), eq("FAILURE"), eq(0L));
        verify(f.trace(), never()).recordNode(anyString(), anyInt(), eq("PLANNING_ROUTE"),
                anyString(), eq("SUCCESS"), anyLong());
        verifyNoInteractions(f.executor(), f.scheduler(), f.projector());
    }

    @Test
    void successfulResumeRoutingStillRecordsASuccessfulChoice() {
        Fixture f = fixture();
        Goal goal = new Goal("goal", "比较资料", "", Map.of(), List.of(), List.of());
        TaskNode node = new TaskNode("read", "资料原文", "web", List.of(), List.of(), List.of("资料"),
                TaskNodeStatus.PENDING, 50, DoneWhen.note(), "", "", 0, "");
        f.store().init("s", "original-execution", goal, new TaskGraph(List.of(node)));

        PlanningRoute route = f.loop().selectRoute(null, TaskPlan.of("继续"), "s", "继续", null, List.of());

        assertTrue(route.useDag());
        verify(f.trace()).recordNode(eq("s"), eq(0), eq("PLANNING_ROUTE"),
                contains("\"route\":\"dag\""), eq("SUCCESS"), eq(0L));
        verifyNoInteractions(f.compiler(), f.executor(), f.scheduler(), f.projector());
    }

    private static void assertPlanFailure(Fixture f, PlanningLoop.RunOutcome result) {
        assertEquals(PlanningLoop.Outcome.FAILED, result.outcome());
        assertEquals("PLAN_VALIDATION_FAILED", result.reason());
        assertFalse(result.goalSatisfied());
        assertFalse(result.answer().isBlank(), "the user must receive a visible failure response");
        verify(f.trace()).recordNode(eq("s"), eq(0), eq("GOAL_COMPILED"),
                contains("\"accepted\":false"), eq("FAILURE"), eq(0L));
        verify(f.trace(), never()).recordNode(anyString(), anyInt(), eq("GOAL_COMPILED"),
                anyString(), eq("SUCCESS"), anyLong());
    }

    private static Fixture fixture() {
        PlannerProperties properties = new PlannerProperties();
        properties.setMaxReplanRetries(0);
        GoalCompiler compiler = mock(GoalCompiler.class);
        Goal goal = new Goal("invalid", "比较资料并交付结论", "", Map.of(), List.of(), List.of());
        when(compiler.compile(any(), anyString(), any())).thenReturn(new GoalCompiler.CompileResult(
                goal, new TaskGraph(List.of()), true, GoalCompiler.CompilationSource.MODEL_EMPTY,
                "MODEL_EMPTY_OR_INVALID_GRAPH"));
        PlannerStateStore store = new PlannerStateStore();
        NodeExecutor executor = mock(NodeExecutor.class);
        GraphScheduler scheduler = mock(GraphScheduler.class);
        TodoStateProjector projector = mock(TodoStateProjector.class);
        PlanningLoop loop = new PlanningLoop(properties, compiler, new PlanValidator(), store,
                scheduler, mock(ToolRouter.class), mock(StepEvaluator.class), mock(RecoveryEngine.class),
                projector, executor, mock(PlannerMetrics.class), mock(SessionLock.class), mock(ToolSuccessStats.class));
        TraceRecorder trace = mock(TraceRecorder.class);
        loop.setTraceRecorder(trace);
        return new Fixture(loop, compiler, store, executor, scheduler, projector, trace);
    }

    private static PlanningLoop.RunOutcome invoke(Fixture f, String request, UserMessage multimodal) {
        return f.loop().runWithOutcome(null, "system", request, multimodal, List.of(), TaskPlan.of(request),
                "s", "fence", "execution", null, PlanningRoute.dag("model", "多个产出与依赖验收"));
    }

    private record Fixture(PlanningLoop loop, GoalCompiler compiler, PlannerStateStore store,
                           NodeExecutor executor, GraphScheduler scheduler, TodoStateProjector projector,
                           TraceRecorder trace) {}
}
