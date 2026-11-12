package com.miniagent.agent.planner;

import com.miniagent.agent.core.NodeExecutor;
import com.miniagent.agent.permission.*;
import com.miniagent.agent.task.TaskPlanFactory;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Tests the real entry router, including mode independence and task-scoped resumption. */
class PlanningLoopRoutingTest {
    private static final String DOCUMENT_TASK = "根据这个技术需求文档给我设计一个大屏工作台，大屏用来展示虚拟仿真其他要可以放在pc浏览器中";
    private final PlannerProperties properties = new PlannerProperties();
    private final PlannerStateStore state = new PlannerStateStore();
    private final GoalCompiler compiler = spy(new GoalCompiler(properties));
    private final PlanningLoop planner = new PlanningLoop(properties, compiler, new PlanValidator(), state,
            mock(GraphScheduler.class), mock(ToolRouter.class), mock(StepEvaluator.class),
            mock(RecoveryEngine.class), mock(TodoStateProjector.class), mock(NodeExecutor.class),
            mock(PlannerMetrics.class), mock(SessionLock.class), mock(ToolSuccessStats.class));
    private final TaskPlanFactory plans = new TaskPlanFactory();

    @AfterEach void clearContext() { PermissionContext.clear(); }

    @ParameterizedTest
    @EnumSource(value = PermissionMode.class, names = {"DEFAULT", "ACCEPT_EDITS", "READ_ONLY", "ASK"})
    void semanticDagIsAvailableWithoutChangingPermissions(PermissionMode mode) {
        PermissionContext.force("session", mode, ConfirmPolicy.AUTO, false);
        ChatModel model = model("dag", true, "设计一个大屏工作台");
        var route = planner.selectRoute(model, plans.build(DOCUMENT_TASK), "session", DOCUMENT_TASK, null, List.of());
        assertTrue(route.useDag());
        assertTrue(route.artifactDeliveryRequested());
        assertEquals(mode, PermissionContext.mode());
        assertFalse(PermissionContext.planApproved());
        verify(model, times(1)).chat(any(ChatRequest.class));
        if (mode == PermissionMode.READ_ONLY)
            assertFalse(PermissionPolicy.allowInSpecs(mode, false, "write_file"));
    }

    @Test void attachmentsAndRecentHistoryReachTheRouter() {
        ChatModel model = model("dag", true, "设计一个大屏工作台");
        String task = DOCUMENT_TASK + "\n===== 附件#1: 技术要求.docx =====\n虚拟仿真、AI算法接入、闭环控制、测评与硬件监控。";
        assertTrue(planner.selectRoute(model, plans.build(task), "session", task, null,
                List.of(UserMessage.from("屏幕要兼容1920和1366宽度"))).useDag());
        var request = org.mockito.ArgumentCaptor.forClass(ChatRequest.class);
        verify(model).chat(request.capture());
        String input = request.getValue().messages().get(1).toString();
        assertTrue(input.contains("技术要求.docx"));
        assertTrue(input.contains("1366"));
    }

    @Test void simpleNewsAndLongExplanationsCanRemainDirect() {
        ChatModel model = model("direct", false, "");
        assertFalse(planner.selectRoute(model, plans.build("帮我查询今日AI新闻"), "session",
                "帮我查询今日AI新闻", null, List.of()).useDag());
        String longQuestion = "请解释以下内容：" + "复杂技术名词、文件和系统。".repeat(500);
        assertFalse(planner.selectRoute(model, plans.build(longQuestion), "session", longQuestion, null, List.of()).useDag());
    }

    @Test void explicitPlanModeKeepsDagEvenWhenSemanticStrategyIsDirect() {
        PermissionContext.force("session", PermissionMode.PLAN, ConfirmPolicy.DANGEROUS, false);
        ChatModel model = model("direct", false, "");
        assertTrue(planner.selectRoute(model, plans.build(DOCUMENT_TASK), "session", DOCUMENT_TASK, null, List.of()).useDag());
        assertFalse(PermissionContext.planApproved());
    }

    @Test void disabledAutomaticPlanningRetainsExplicitPlanMode() {
        properties.setAutoPlanning(false);
        ChatModel model = model("direct", false, "");
        assertFalse(planner.selectRoute(model, plans.build(DOCUMENT_TASK), "session", DOCUMENT_TASK, null, List.of()).useDag());
        PermissionContext.force("session", PermissionMode.PLAN, ConfirmPolicy.DANGEROUS, false);
        assertTrue(planner.selectRoute(model, plans.build(DOCUMENT_TASK), "session", DOCUMENT_TASK, null, List.of()).useDag());
        properties.setEnabled(false);
        assertFalse(planner.selectRoute(model, plans.build(DOCUMENT_TASK), "session", DOCUMENT_TASK, null, List.of()).useDag());
        verify(model, times(1)).chat(any(ChatRequest.class));
    }

    @Test void continuationResumesCurrentGraphEvenAfterAutomaticRoutingIsTurnedOff() {
        state.init("session", "exec", new Goal("g", "原任务", "", Map.of(), List.of(), List.of()),
                new TaskGraph(List.of(new TaskNode("n", "核验", "web", List.of(), List.of(), List.of("资料"),
                        TaskNodeStatus.PENDING, 50, DoneWhen.note(), "", "", 0, ""))));
        properties.setAutoPlanning(false);
        ChatModel model = mock(ChatModel.class);
        assertTrue(planner.selectRoute(model, plans.build("继续"), "session", "继续", null, List.of()).useDag());
        assertFalse(planner.selectRoute(model, plans.build("为什么天空是蓝色？"), "session",
                "为什么天空是蓝色？", null, List.of()).useDag());
        verifyNoInteractions(model);
    }

    @Test void missingOrBrokenModelFallsBackWithoutInventingAPlan() {
        assertFalse(planner.selectRoute(null, plans.build(DOCUMENT_TASK), "session", DOCUMENT_TASK, null, List.of()).useDag());
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenThrow(new IllegalStateException("provider failed"));
        assertEquals("fallback", planner.selectRoute(model, plans.build(DOCUMENT_TASK), "session", DOCUMENT_TASK, null, List.of()).source());
        assertTrue(state.get("session").isEmpty());
    }

    @Test void clearNewQuestionDoesNotResumeAnOldClarificationGraph() {
        Goal clarify = new Goal("g", "待澄清的任务", "", Goal.TASK_TYPE_CLARIFY,
                Map.of(), List.of(), List.of());
        state.init("session", "exec", clarify, GoalCompiler.clarifyGraph());
        ChatModel model = model("direct", false, "");
        String question = "换个问题，为什么天空是蓝色？";
        assertFalse(planner.selectRoute(model, plans.build(question), "session", question, null, List.of()).useDag());
        verify(model).chat(any(ChatRequest.class));
        assertEquals("g", state.get("session").orElseThrow().goal().goalId());
    }

    private static ChatModel model(String route, boolean artifact, String evidence) {
        ChatModel model = mock(ChatModel.class);
        String json = "{\"route\":\"" + route + "\",\"reason\":\"依赖与验收判定\",\"artifactDelivery\":{\"requested\":"
                + artifact + ",\"evidence\":\"" + evidence + "\"}}";
        when(model.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder().aiMessage(AiMessage.from(json)).build());
        return model;
    }
}
