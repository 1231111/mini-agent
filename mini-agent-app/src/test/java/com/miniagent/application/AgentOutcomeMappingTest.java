package com.miniagent.application;

import com.miniagent.agent.core.TaskExecutionContext;
import com.miniagent.agent.planner.PlanningLoop;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for task outcome and failure-event attribution.
 */
class AgentOutcomeMappingTest {

    private static PlanningLoop.RunOutcome outcome(PlanningLoop.Outcome kind) {
        return new PlanningLoop.RunOutcome("答复文本", kind, "TEST_REASON");
    }

    @Test
    void unfinishedAndFailedMustNotBeCompleted() {
        assertTrue(AgentChatApplicationService.meansUnfinished(outcome(PlanningLoop.Outcome.UNFINISHED)),
                "卡住了不能算完成");
        assertTrue(AgentChatApplicationService.meansUnfinished(outcome(PlanningLoop.Outcome.FAILED)),
                "运行出错不能算完成");
    }

    @Test
    void completedAndHumanWaitAreNotFailures() {
        assertFalse(AgentChatApplicationService.meansUnfinished(outcome(PlanningLoop.Outcome.COMPLETED)),
                "真正完成不该被记为失败");
        assertFalse(AgentChatApplicationService.meansUnfinished(
                        outcome(PlanningLoop.Outcome.BLOCKED_ON_HUMAN)),
                "在等人既不是完成也不是失败（UI 需要显示等待，而不是报错）");
    }

    @Test
    void nullOutcomeKeepsLegacyBehaviour() {
        assertFalse(AgentChatApplicationService.meansUnfinished(null),
                "直跑路径没有规划器结局，不能因此被判失败");
    }

    @Test
    void onlyCompletedCountsAsGoalSatisfied() {
        assertTrue(outcome(PlanningLoop.Outcome.COMPLETED).goalSatisfied());
        assertFalse(outcome(PlanningLoop.Outcome.UNFINISHED).goalSatisfied());
        assertFalse(outcome(PlanningLoop.Outcome.BLOCKED_ON_HUMAN).goalSatisfied());
        assertFalse(outcome(PlanningLoop.Outcome.FAILED).goalSatisfied());
    }

    @Test
    void unfinishedOutcomeCarriesAReasonForOperators() {
        PlanningLoop.RunOutcome u = outcome(PlanningLoop.Outcome.UNFINISHED);
        assertTrue(u.reason() != null && !u.reason().isBlank(),
                "失败原因码要能被运维/指标消费，否则线上只能看到『任务未达成目标: 』");
    }

    @Test
    void taskIdCapturedBeforeScopeClosesRemainsAvailableForFailureEvent() {
        long[] failureEventTaskId = {0L};
        try (TaskExecutionContext.Scope ignored = TaskExecutionContext.bind(27L)) {
            AgentChatApplicationService.captureTaskId(failureEventTaskId, TaskExecutionContext.currentTaskId());
            assertEquals(27L, TaskExecutionContext.currentTaskId());
        }

        assertEquals(0L, TaskExecutionContext.currentTaskId(), "任务 scope 关闭后 ThreadLocal 应恢复");
        assertEquals(27L, failureEventTaskId[0], "异常事件必须使用已捕获的任务 ID，而不是恢复后的 ThreadLocal");
    }
}
