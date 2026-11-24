package com.miniagent.agent.planner;

import com.miniagent.agent.scope.TaskBoundary;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SemanticPlanningRouterTaskBoundaryTest {

    @Test
    void routesAnIndependentRequestIntoANewTaskScope() throws Exception {
        PlanningRoute route = SemanticPlanningRouter.parse("""
                {"route":"dag","reason":"汽车战略分析有独立目标与交付。",
                 "taskBoundary":"NEW",
                 "dagAction":"PLAN",
                 "artifactDelivery":{"requested":false,"evidence":""}}
                """);

        assertThat(route.resolved()).isTrue();
        assertThat(route.useDag()).isTrue();
        assertThat(route.taskBoundary()).isEqualTo(TaskBoundary.NEW);
        assertThat(route.dagAction()).isEqualTo(PlanningRoute.DagAction.PLAN);
    }

    @Test
    void keepsContinuationInCurrentScope() throws Exception {
        PlanningRoute route = SemanticPlanningRouter.parse("""
                {"route":"dag","reason":"继续验收当前页面。",
                 "taskBoundary":"SAME",
                 "dagAction":"RESUME_EXISTING",
                 "artifactDelivery":{"requested":false,"evidence":""}}
                """);

        assertThat(route.taskBoundary()).isEqualTo(TaskBoundary.SAME);
        assertThat(route.dagAction()).isEqualTo(PlanningRoute.DagAction.RESUME_EXISTING);
    }

    @Test
    void recognizesExplicitResumeOfAPausedTask() throws Exception {
        PlanningRoute route = SemanticPlanningRouter.parse("""
                {"route":"dag","reason":"用户明确要求继续上一个未完成任务。",
                 "taskBoundary":"RESUME",
                 "dagAction":"RESUME_EXISTING",
                 "artifactDelivery":{"requested":false,"evidence":""}}
                """);

        assertThat(route.useDag()).isTrue();
        assertThat(route.taskBoundary()).isEqualTo(TaskBoundary.RESUME);
        assertThat(route.dagAction()).isEqualTo(PlanningRoute.DagAction.RESUME_EXISTING);
    }

    @Test
    void asksTheSemanticRouterToChooseResumeForAResubmittedSameTask() {
        String output = """
                {"route":"dag","reason":"请求继续同一未完成任务。",
                 "taskBoundary":"SAME","dagAction":"RESUME_EXISTING",
                 "artifactDelivery":{"requested":false,"evidence":""}}
                """;
        ChatModel model = mock(ChatModel.class);
        ChatResponse response = mock(ChatResponse.class);
        when(response.aiMessage()).thenReturn(AiMessage.from(output));
        when(model.chat(any(ChatRequest.class))).thenReturn(response);

        PlanningRoute route = new SemanticPlanningRouter().decide(model,
                "继续将单机版游戏改造成局域网联机版", null, List.of(),
                "objective: 将单机版游戏改造成局域网联机版\n- [FAILED] 完成联机改造", "");

        assertThat(route.resolved()).isTrue();
        assertThat(route.taskBoundary()).isEqualTo(TaskBoundary.SAME);
        assertThat(route.dagAction()).isEqualTo(PlanningRoute.DagAction.RESUME_EXISTING);
        verify(model).chat(any(ChatRequest.class));
    }

    @Test
    void defaultsMissingBoundaryToSameAndRejectsUnknownBoundaryOrDagAction() throws Exception {
        PlanningRoute missing = SemanticPlanningRouter.parse("""
                {"route":"direct","reason":"普通问答。",
                 "dagAction":"PLAN",
                 "artifactDelivery":{"requested":false,"evidence":""}}
                """);
        PlanningRoute unknown = SemanticPlanningRouter.parse("""
                {"route":"direct","reason":"普通问答。","taskBoundary":"ARCHIVE_ALL",
                 "dagAction":"PLAN",
                 "artifactDelivery":{"requested":false,"evidence":""}}
                """);
        PlanningRoute missingAction = SemanticPlanningRouter.parse("""
                {"route":"dag","reason":"继续现有任务。","taskBoundary":"SAME",
                 "artifactDelivery":{"requested":false,"evidence":""}}
                """);
        PlanningRoute conflictingAction = SemanticPlanningRouter.parse("""
                {"route":"dag","reason":"新任务。","taskBoundary":"NEW",
                 "dagAction":"RESUME_EXISTING",
                 "artifactDelivery":{"requested":false,"evidence":""}}
                """);

        assertThat(missing.resolved()).isTrue();
        assertThat(missing.taskBoundary()).isEqualTo(TaskBoundary.SAME);
        assertThat(unknown.resolved()).isFalse();
        assertThat(missingAction.resolved()).isFalse();
        assertThat(conflictingAction.resolved()).isFalse();
    }
}
