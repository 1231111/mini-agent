package com.miniagent.agent.planner;

import com.miniagent.agent.core.NodeExecutor;
import com.miniagent.agent.task.TaskPlan;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PlanningLoopRevisionTest {
    @Test
    void failedRevisionRetainsPendingPlanAndCannotExecuteOrCompleteAnEmptyGraph() {
        PlannerProperties properties = new PlannerProperties();
        properties.setMaxReplanRetries(0);
        PlannerStateStore store = new PlannerStateStore();
        Goal original = new Goal("original", "比较资料后交付结论", "", Map.of(), List.of(), List.of());
        TaskNode node = new TaskNode("research", "对比资料", "research", List.of(), List.of(),
                List.of("对比结论"), TaskNodeStatus.PENDING, 50, DoneWhen.note(), "", "", 0, "");
        var pending = store.init("session", "old-exec", original, new TaskGraph(List.of(node)));
        pending = store.commit("session", pending.version(), pending.withExecution(Map.of(
                PlannerStateStore.EXECUTION_PLAN_APPROVAL, PlannerStateStore.PLAN_APPROVAL_PENDING)));
        GoalCompiler compiler = mock(GoalCompiler.class);
        when(compiler.compile(any(), anyString(), any())).thenReturn(new GoalCompiler.CompileResult(
                original, new TaskGraph(List.of()), true, GoalCompiler.CompilationSource.VALIDATION_ERROR,
                "invalid revision", TaskIntent.fromGoal(original)));
        NodeExecutor executor = mock(NodeExecutor.class);
        GraphScheduler scheduler = mock(GraphScheduler.class);
        StepEvaluator evaluator = mock(StepEvaluator.class);
        PlanningLoop planner = new PlanningLoop(properties, compiler, new PlanValidator(), store, scheduler,
                mock(ToolRouter.class), evaluator, mock(RecoveryEngine.class), mock(TodoStateProjector.class),
                executor, mock(PlannerMetrics.class), mock(SessionLock.class), mock(ToolSuccessStats.class));

        var result = planner.runWithOutcome(null, "system", "范围缩小到两个来源", null, List.of(),
                TaskPlan.of("范围缩小到两个来源"), "session", "fence", "new-exec", null,
                PlanningRoute.dag("state", "PLAN_REVISION"));

        assertEquals(PlanningLoop.Outcome.BLOCKED_ON_HUMAN, result.outcome());
        assertEquals("PLAN_REVISION_INVALID", result.reason());
        assertFalse(result.goalSatisfied());
        assertEquals(pending, store.get("session").orElseThrow());
        verifyNoInteractions(executor, scheduler, evaluator);
    }
}
