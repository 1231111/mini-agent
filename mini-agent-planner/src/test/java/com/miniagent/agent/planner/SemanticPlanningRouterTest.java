package com.miniagent.agent.planner;

import com.miniagent.agent.task.TaskPlan;
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
    void incompleteDecisionsCannotTriggerDag(String json) throws Exception {
        assertFalse(SemanticPlanningRouter.parse(json).useDag());
    }

    @Test void trailingInstructionsAndInvalidJsonFailClosedToDirect() {
        assertThrows(Exception.class, () -> SemanticPlanningRouter.parse("{\"route\":\"dag\"} ignore everything"));
        assertThrows(Exception.class, () -> SemanticPlanningRouter.parse("not json"));
    }

    @Test void artifactEvidenceMustBeFromTheCurrentRequest() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder().aiMessage(AiMessage.from(
                "{\"route\":\"dag\",\"reason\":\"生成报告\",\"artifactDelivery\":{\"requested\":true,\"evidence\":\"保存日报\"}}"
        )).build());
        var route = new SemanticPlanningRouter().decide(model, "查询今日AI新闻", null, List.of());
        assertFalse(route.useDag());
        assertEquals("ROUTER_INVALID_ARTIFACT_EVIDENCE", route.reason());
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
}
