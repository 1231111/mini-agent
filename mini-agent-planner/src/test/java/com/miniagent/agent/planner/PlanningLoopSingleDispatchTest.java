package com.miniagent.agent.planner;

import com.miniagent.agent.core.AgentLoop;
import com.miniagent.agent.core.NodeExecutor;
import com.miniagent.agent.permission.PermissionContext;
import com.miniagent.agent.permission.PermissionMode;
import com.miniagent.agent.task.TaskPlan;
import com.miniagent.agent.tool.CapabilityRegistry;
import com.miniagent.agent.tool.ToolErrorCode;
import com.miniagent.agent.tool.ToolResult;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlanningLoopSingleDispatchTest {
    @AfterEach
    void clearPermission() {
        PermissionContext.clear();
    }

    @Test
    void configuredBatchCannotMarkUnexecutedNodeSuccessfulUsingAnotherNodesEvidence() {
        Fixture f = fixture();
        when(f.executor().execute(any(), anyString(), anyString(), any(), anyList(), anyInt(), any(), any()))
                .thenReturn(result("上海今日晴，20度"), result("北京今日多云，10度"));

        PlanningLoop.RunOutcome result = invoke(f);

        assertTrue(result.goalSatisfied());
        TaskGraph graph = f.store().get("s").orElseThrow().graph();
        assertEquals("上海今日晴，20度", graph.byId("shanghai").output());
        assertEquals("北京今日多云，10度", graph.byId("beijing").output());
        verify(f.executor(), times(2)).execute(any(), anyString(), anyString(), any(), anyList(), anyInt(), any(), any());
    }

    @Test
    void secondNodeFailureCannotReuseSuccessfulFirstNodeEvidence() {
        Fixture f = fixture();
        ToolResult failed = ToolResult.failure(ToolErrorCode.EXECUTION_FAILED, "北京天气查询失败", false, "请求失败");
        when(f.executor().execute(any(), anyString(), anyString(), any(), anyList(), anyInt(), any(), any()))
                .thenReturn(result("上海今日晴，20度"), new AgentLoop.LoopOutcome("查询失败", "FAILURE",
                        List.of(ToolExecutionResultMessage.from("search", "web_search", failed.legacyText())),
                        List.of("web_search"), failed.errorCode(), failed));

        assertFalse(invoke(f).goalSatisfied());
        TaskGraph graph = f.store().get("s").orElseThrow().graph();
        assertEquals(TaskNodeStatus.SUCCESS, graph.byId("shanghai").status());
        assertNotEquals(TaskNodeStatus.SUCCESS, graph.byId("beijing").status());
        verify(f.executor(), times(2)).execute(any(), anyString(), anyString(), any(), anyList(), anyInt(), any(), any());
    }

    private static Fixture fixture() {
        PlannerProperties properties = new PlannerProperties();
        properties.setProposalBatchSize(2);
        properties.setMaxRecoveries(0);
        properties.setMaxOuterRounds(4);
        PlannerStateStore store = new PlannerStateStore();
        store.init("s", "execution", new Goal("goal", "查询两地天气", "", Map.of(), List.of(), List.of()),
                new TaskGraph(List.of(node("shanghai", "上海天气"), node("beijing", "北京天气"))));
        NodeExecutor executor = mock(NodeExecutor.class);
        CapabilityRegistry capabilities = mock(CapabilityRegistry.class);
        when(capabilities.toolsFor(anyString())).thenReturn(List.of("web_search"));
        SessionLock lock = mock(SessionLock.class);
        when(lock.renewSessionLock(anyString(), anyString())).thenReturn(true);
        PlannerMetrics metrics = new PlannerMetrics();
        PlanningLoop loop = new PlanningLoop(properties, mock(GoalCompiler.class), new PlanValidator(), store,
                new GraphScheduler(properties), new ToolRouter(capabilities), new StepEvaluator(properties, metrics, null),
                new RecoveryEngine(store, capabilities, properties), mock(TodoStateProjector.class), executor,
                metrics, lock, new ToolSuccessStats());
        PermissionContext.force("s", PermissionMode.PLAN, true);
        return new Fixture(loop, store, executor);
    }

    private static TaskNode node(String id, String name) {
        return new TaskNode(id, name, "web_search", List.of(), List.of(), List.of(id),
                TaskNodeStatus.PENDING, 1, DoneWhen.note(), "", "", 0, "");
    }

    private static AgentLoop.LoopOutcome result(String text) {
        return new AgentLoop.LoopOutcome(text, "SUCCESS",
                List.of(ToolExecutionResultMessage.from("search", "web_search", text)),
                List.of("web_search"), ToolErrorCode.NONE, ToolResult.success(text));
    }

    private static PlanningLoop.RunOutcome invoke(Fixture f) {
        return f.loop().runWithOutcome(null, "system", "继续", null, List.of(), TaskPlan.of("查询两地天气"),
                "s", "fence", "execution", null);
    }

    private record Fixture(PlanningLoop loop, PlannerStateStore store, NodeExecutor executor) {}
}
