package com.miniagent.agent.planner;

import com.miniagent.agent.scope.TaskBoundary;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SemanticPlanningRouterTaskBoundaryTest {

    @Test
    void routesAnIndependentRequestIntoANewTaskScope() throws Exception {
        PlanningRoute route = SemanticPlanningRouter.parse("""
                {"route":"dag","reason":"汽车战略分析有独立目标与交付。",
                 "taskBoundary":"NEW",
                 "artifactDelivery":{"requested":false,"evidence":""}}
                """);

        assertThat(route.resolved()).isTrue();
        assertThat(route.useDag()).isTrue();
        assertThat(route.taskBoundary()).isEqualTo(TaskBoundary.NEW);
    }

    @Test
    void keepsContinuationInCurrentScope() throws Exception {
        PlanningRoute route = SemanticPlanningRouter.parse("""
                {"route":"dag","reason":"继续验收当前页面。",
                 "taskBoundary":"SAME",
                 "artifactDelivery":{"requested":false,"evidence":""}}
                """);

        assertThat(route.taskBoundary()).isEqualTo(TaskBoundary.SAME);
    }

    @Test
    void recognizesExplicitResumeOfAPausedTask() throws Exception {
        PlanningRoute route = SemanticPlanningRouter.parse("""
                {"route":"dag","reason":"用户明确要求继续上一个未完成任务。",
                 "taskBoundary":"RESUME",
                 "artifactDelivery":{"requested":false,"evidence":""}}
                """);

        assertThat(route.useDag()).isTrue();
        assertThat(route.taskBoundary()).isEqualTo(TaskBoundary.RESUME);
    }

    @Test
    void defaultsMissingBoundaryToSameAndRejectsUnknownBoundary() throws Exception {
        PlanningRoute missing = SemanticPlanningRouter.parse("""
                {"route":"direct","reason":"普通问答。",
                 "artifactDelivery":{"requested":false,"evidence":""}}
                """);
        PlanningRoute unknown = SemanticPlanningRouter.parse("""
                {"route":"direct","reason":"普通问答。","taskBoundary":"ARCHIVE_ALL",
                 "artifactDelivery":{"requested":false,"evidence":""}}
                """);

        assertThat(missing.resolved()).isTrue();
        assertThat(missing.taskBoundary()).isEqualTo(TaskBoundary.SAME);
        assertThat(unknown.resolved()).isFalse();
    }
}
