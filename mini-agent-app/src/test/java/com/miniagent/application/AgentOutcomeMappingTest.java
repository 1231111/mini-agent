package com.miniagent.application;

import com.miniagent.agent.planner.PlanningLoop;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "没做完"不能记为完成 —— 这条不变式的回归测试。
 *
 * <p>背景：规划器的图死锁、恢复耗尽、外圈轮次用尽、任务图无法验收，此前全都只返回一句
 * "已按规划图推进任务（version=…）"，调用方一律 {@code markCompleted}。结果是
 * **任务状态、完成率口径、以及用户对"做完了"的判断同时失真** —— 生产环境里最贵的一类 bug，
 * 因为它让"成功率"这个指标本身失去意义。</p>
 *
 * <p>现在 {@code PlanningLoop.runWithOutcome} 会给出结局，由本方法决定是否记为失败。
 * {@code BLOCKED_ON_HUMAN} 刻意不算失败：它在等人，既不是完成也不是失败。</p>
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
}
