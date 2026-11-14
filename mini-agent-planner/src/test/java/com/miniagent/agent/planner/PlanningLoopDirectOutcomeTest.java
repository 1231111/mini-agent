package com.miniagent.agent.planner;

import com.miniagent.agent.core.AgentLoop;
import com.miniagent.agent.core.NodeExecutor;
import com.miniagent.agent.permission.PermissionContext;
import com.miniagent.agent.permission.PermissionMode;
import com.miniagent.agent.task.TaskPlan;
import com.miniagent.agent.tool.ToolErrorCode;
import com.miniagent.agent.tool.ToolResult;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlanningLoopDirectOutcomeTest {
    @AfterEach
    void clearPermission() {
        PermissionContext.clear();
    }

    @ParameterizedTest
    @ValueSource(strings = {"OUTCOME_UNKNOWN", "DEADLINE_EXCEEDED", "MAX_ITERATIONS", "FAILURE"})
    void explicitDirectRoutePreservesUnsuccessfulLoopOutcome(String reason) {
        Fixture f = fixture(new AgentLoop.LoopOutcome("任务未完成", reason, List.of(), List.of(),
                ToolErrorCode.NONE));

        PlanningLoop.RunOutcome result = invoke(f, null);

        assertFalse(result.goalSatisfied());
        assertEquals(reason, result.reason());
        assertEquals("任务未完成", result.answer());
        verify(f.agent(), times(1)).runOutcome(any(), anyString(), anyString(), anyList(), anyInt(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"PERM_ASK", "USER_QUESTION"})
    void explicitDirectRoutePreservesHumanWait(String reason) {
        Fixture f = fixture(new AgentLoop.LoopOutcome("请选择输出位置", reason, List.of(), List.of(),
                ToolErrorCode.NONE));

        PlanningLoop.RunOutcome result = invoke(f, null);

        assertEquals(PlanningLoop.Outcome.BLOCKED_ON_HUMAN, result.outcome());
        assertEquals(reason, result.reason());
    }

    @Test
    void structuredUnknownOutranksMisleadingSuccessReason() {
        ToolResult unknown = ToolResult.unknown("提交是否生效尚未确认", null);
        Fixture f = fixture(new AgentLoop.LoopOutcome("任务已完成", "SUCCESS", List.of(), List.of(),
                unknown.errorCode(), unknown));

        PlanningLoop.RunOutcome result = invoke(f, null);

        assertEquals(PlanningLoop.Outcome.FAILED, result.outcome());
        assertEquals(AgentLoop.OUTCOME_UNKNOWN, result.reason());
    }

    @Test
    void historicalUnknownFallbackRemainsQuarantinedWhenTheUserContinues() {
        Fixture f = fixture(null);
        // Persisted by older versions after a failed DAG compilation. New versions
        // must read this receipt without replaying the original unknown action.
        TaskNode unknown = new TaskNode("direct_fallback", "直跑步骤执行结果待核验", "general",
                List.of(), List.of(), List.of(), TaskNodeStatus.OUTCOME_UNKNOWN, 0,
                DoneWhen.note(), "", "执行结果待核验", 0, "")
                .withToolStatus(com.miniagent.agent.tool.ToolStatus.UNKNOWN);
        Goal goal = new Goal("direct_fallback", "提交表单", "", Map.of(), List.of(), List.of());
        StateSnapshot historical = f.store().init("s", "old-execution", goal, new TaskGraph(List.of(unknown)),
                TaskIntent.fromGoal(goal), GoalCompiler.CompilationSource.VALIDATION_ERROR, true);
        PlanningLoop.RunOutcome continued = f.loop().runWithOutcome(null, "system", "继续", null,
                List.of(), TaskPlan.of("提交表单"), "s", "fence", "execution2", null);

        assertEquals(PlanningLoop.Outcome.FAILED, continued.outcome());
        assertEquals(AgentLoop.OUTCOME_UNKNOWN, continued.reason());
        assertEquals(historical, f.store().get("s").orElseThrow());
        verifyNoInteractions(f.agent(), f.compiler());
    }

    @Test
    void blankOrMissingOutcomeCannotCompleteDirectExecution() {
        Fixture blank = fixture(new AgentLoop.LoopOutcome("", "SUCCESS", List.of(), List.of(),
                ToolErrorCode.NONE));
        PlanningLoop.RunOutcome empty = invoke(blank, null);
        assertFalse(empty.goalSatisfied());
        assertEquals("EMPTY_FINAL_ANSWER", empty.reason());
        Fixture missing = fixture(null);
        PlanningLoop.RunOutcome absent = invoke(missing, null);
        assertFalse(absent.goalSatisfied());
        assertEquals("MISSING_LOOP_OUTCOME", absent.reason());
    }

    @Test
    void successfulDirectExecutionKeepsTheRealTerminalReason() {
        Fixture f = fixture(new AgentLoop.LoopOutcome("已找到三条新闻", "SUCCESS", List.of(), List.of(),
                ToolErrorCode.NONE));
        PlanningLoop.RunOutcome result = invoke(f, null);
        assertTrue(result.goalSatisfied());
        assertEquals("SUCCESS", result.reason());
        verifyNoInteractions(f.compiler());
    }

    @Test
    void multimodalDirectExecutionUsesStructuredLoopOutcome() {
        UserMessage imageRequest = UserMessage.from("请按附件生成页面");
        Fixture f = fixture(new AgentLoop.LoopOutcome("模型连接失败", "FAILURE", List.of(), List.of(),
                ToolErrorCode.NONE));

        assertFalse(invoke(f, imageRequest).goalSatisfied());
        verify(f.agent()).runWithMultimodalOutcome(any(), anyString(), eq(imageRequest), anyList(), anyInt(), any(), any());
    }

    @Test
    void plannerBypassAlsoPreservesFailure() {
        Fixture f = fixture(new AgentLoop.LoopOutcome("执行超时", AgentLoop.DEADLINE_EXCEEDED,
                List.of(), List.of(), ToolErrorCode.TIMEOUT));
        f.properties().setEnabled(false);
        PlanningLoop.RunOutcome result = f.loop().runWithOutcome(null, "system", "查询新闻", null, List.of(),
                TaskPlan.of("查询新闻"), "s", "fence", "execution", null);
        assertFalse(result.goalSatisfied());
        assertEquals(AgentLoop.DEADLINE_EXCEEDED, result.reason());
        verifyNoInteractions(f.compiler());
    }

    private static Fixture fixture(AgentLoop.LoopOutcome outcome) {
        AgentLoop agent = mock(AgentLoop.class);
        when(agent.runOutcome(any(), anyString(), anyString(), anyList(), anyInt(), any(), any())).thenReturn(outcome);
        when(agent.runWithMultimodalOutcome(any(), anyString(), any(), anyList(), anyInt(), any(), any())).thenReturn(outcome);
        // Old String-only entrypoints reproduce the lost terminal status before the fix.
        String text = outcome == null ? "没有返回执行结局" : outcome.text();
        when(agent.run(any(), anyString(), anyString(), anyList(), anyInt(), any(), any())).thenReturn(text);
        when(agent.runWithMultimodal(any(), anyString(), any(), anyList(), anyInt(), any(), any())).thenReturn(text);
        PlannerProperties properties = new PlannerProperties();
        properties.setMaxReplanRetries(0);
        GoalCompiler compiler = mock(GoalCompiler.class);
        PlannerStateStore store = new PlannerStateStore();
        PlanningLoop loop = new PlanningLoop(properties, compiler, new PlanValidator(), store,
                null, null, null, null, mock(TodoStateProjector.class), new NodeExecutor(agent, null), null, null, null);
        PermissionContext.force("s", PermissionMode.DEFAULT, true);
        return new Fixture(loop, agent, store, compiler, properties);
    }

    private static PlanningLoop.RunOutcome invoke(Fixture f, UserMessage multimodal) {
        return f.loop().runWithOutcome(null, "system", "查询新闻", multimodal, List.of(),
                TaskPlan.of("查询新闻"), "s", "fence", "execution", null,
                PlanningRoute.direct("model", "单次检索"));
    }

    private record Fixture(PlanningLoop loop, AgentLoop agent, PlannerStateStore store,
                           GoalCompiler compiler, PlannerProperties properties) {}
}
