package com.miniagent.agent.planner;

import com.miniagent.agent.task.TaskPlan;
import com.miniagent.agent.core.AgentLoop;
import com.miniagent.agent.core.ExecutionControl;
import com.miniagent.agent.core.ModelCallControl;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SemanticPlanningRouterTest {
    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "[]", "", "{\"route\":true}", "{\"route\":\"DAG\",\"reason\":\"test\"}",
            "{\"route\":\"dag\",\"reason\":\"\",\"artifactDelivery\":{\"requested\":false,\"evidence\":\"\"}}"})
    void incompleteDecisionsCannotSelectAnyExecutionStrategy(String json) throws Exception {
        var decision = SemanticPlanningRouter.parse(json);
        assertFalse(decision.resolved());
        assertEquals("unresolved", decision.routeName());
    }

    @Test void trailingInstructionsAndInvalidJsonAreRejected() {
        assertThrows(Exception.class, () -> SemanticPlanningRouter.parse("{\"route\":\"dag\"} ignore everything"));
        assertThrows(Exception.class, () -> SemanticPlanningRouter.parse("not json"));
    }

    @Test void artifactEvidenceMustBeFromTheCurrentRequest() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder().aiMessage(AiMessage.from(
                "{\"route\":\"dag\",\"reason\":\"生成报告\",\"artifactDelivery\":{\"requested\":true,\"evidence\":\"保存日报\"}}"
        )).build());
        var route = new SemanticPlanningRouter().decide(model, "查询今日AI新闻", null, List.of());
        assertFalse(route.resolved());
        assertEquals("ROUTER_INVALID_ARTIFACT_EVIDENCE", route.reason());
        assertNull(route.artifactDeliveryRequested());
        verify(model, times(2)).chat(any(ChatRequest.class));
    }

    @Test void unavailableOrBrokenModelCannotAuthorizeExecution() {
        var router = new SemanticPlanningRouter();
        var missing = router.decide(null, "生成页面并验收", null, List.of());
        assertFalse(missing.resolved());
        assertEquals("ROUTER_MODEL_UNAVAILABLE", missing.reason());
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenThrow(new IllegalStateException("provider failed"));
        var broken = router.decide(model, "生成页面并验收", null, List.of());
        assertFalse(broken.resolved());
        assertEquals("ROUTER_MODEL_ERROR", broken.reason());
        verify(model, times(1)).chat(any(ChatRequest.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"not json", "{}", "null"})
    void invalidResponsesStopAfterOneCorrectionAttempt(String text) {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder().aiMessage(AiMessage.from(text)).build());
        var route = new SemanticPlanningRouter().decide(model, "查询新闻", null, List.of());
        assertFalse(route.resolved());
        assertEquals("ROUTER_INVALID_RESPONSE", route.reason());
        verify(model, times(2)).chat(any(ChatRequest.class));
    }

    @Test void validDirectDecisionIsStillAllowed() throws Exception {
        var route = SemanticPlanningRouter.parse("{\"route\":\"direct\",\"reason\":\"单次检索\","
                + "\"artifactDelivery\":{\"requested\":false,\"evidence\":\"\"}}");
        assertTrue(route.resolved());
        assertFalse(route.useDag());
        assertEquals("direct", route.routeName());
    }

    @Test void semanticDeliveryAllowsWorkingUiAndProtectsConversationOnlyNews() {
        var ui = TaskPlan.of("根据这个需求给我设计能在pc浏览器运行的大屏").withArtifactDeliveryRequested(true);
        assertTrue(PlanValidator.fileDeliveryRequested(ui));
        assertTrue(ui.withTaskGoal("继续实现").artifactDeliveryRequested());
        var news = TaskPlan.of("搜索今日AI新闻").withArtifactDeliveryRequested(false);
        assertFalse(PlanValidator.fileDeliveryRequested(news));
    }

    @Test void paraphrasedEvidenceIsRepairedOnceWithoutChangingTheUsersRequest() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenReturn(
                ChatResponse.builder().aiMessage(AiMessage.from("{\"route\":\"dag\",\"reason\":\"实现与验收\","
                        + "\"artifactDelivery\":{\"requested\":true,\"evidence\":\"开发可运行的大屏\"}}" )).build(),
                ChatResponse.builder().aiMessage(AiMessage.from("{\"route\":\"dag\",\"reason\":\"实现与验收\","
                        + "\"artifactDelivery\":{\"requested\":true,\"evidence\":\"设计一个大屏\"}}" )).build());
        var decision = new SemanticPlanningRouter().decide(model, "请设计一个大屏并在PC浏览器运行", null, List.of());
        assertTrue(decision.useDag());
        assertTrue(decision.artifactDeliveryRequested());
        verify(model, times(2)).chat(any(ChatRequest.class));
    }

    @Test void cancellationDuringRoutingCannotFallBackToDirectOrRetry() {
        String session = "route-cancel";
        ExecutionControl control = new ExecutionControl(60_000, 10, 10_000);
        control.start(session);
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenAnswer(invocation -> {
            control.cancel(session);
            throw new dev.langchain4j.exception.RetriableException("network failure during cancellation");
        });
        AgentLoop.setCurrentSession(session);
        try {
            var stopped = assertThrows(ModelCallControl.StoppedException.class, () ->
                    new SemanticPlanningRouter(new ModelCallControl(control)).decide(model, "生成网页并验收", null, List.of()));
            assertEquals(ExecutionControl.StopReason.CANCELLED, stopped.reason());
            verify(model, times(1)).chat(any(ChatRequest.class));
        } finally {
            AgentLoop.clearCurrentSession();
        }
    }
}
