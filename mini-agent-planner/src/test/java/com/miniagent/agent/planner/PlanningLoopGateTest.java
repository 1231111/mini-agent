package com.miniagent.agent.planner;

import com.miniagent.agent.permission.PermissionMode;
import com.miniagent.agent.task.TaskPlan;
import com.miniagent.agent.task.TaskSignals;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 规划器入口的行为契约。
 *
 * <p>默认 Agent 路径不根据 TaskSignals 猜「简单任务/复杂任务」；
 * 只有会话显式处于 PermissionMode.PLAN 时才进入规划器。
 * shouldHandle 本身依赖会话状态和 Spring 协作方，因此这里保留纯函数级的
 * 模式契约测试，实际 shouldHandle 的会话集成测试由 PlanningLoop 的入口测试覆盖。</p>
 */
class PlanningLoopGateTest {

    private static TaskPlan plan(String goal, String signals, boolean structured) {
        return TaskPlan.of(goal, structured, TaskSignals.parse(signals));
    }

    @Test
    void defaultAgentDoesNotNeedTaskComplexityClassifier() {
        // 搜索、写文件、URL 和复杂多步请求都由 AgentLoop 直接交给 LLM。
        // 这些信号不再自动拉起 GoalCompiler。
        assertEquals(PermissionMode.DEFAULT, PermissionMode.from("default"));
        assertEquals(PermissionMode.DEFAULT, PermissionMode.from("normal"));
        assertFalse(plan("搜索今日 AI 动态", "web", false).signals().lightTurn());
        assertTrue(plan("搜索今日 AI 动态", "web", false).signals().actionBearing());
    }

    @Test
    void planModeIsExplicitSessionChoice() {
        assertEquals(PermissionMode.PLAN, PermissionMode.from("plan"));
        assertEquals("plan", PermissionMode.PLAN.wireName());
    }

    @Test
    void readOnlyIsNotPlanMode() {
        assertEquals(PermissionMode.READ_ONLY, PermissionMode.from("read_only"));
        assertFalse(PermissionMode.READ_ONLY == PermissionMode.PLAN);
    }

    @Test
    void nullPlanSignalsRemainSafe() {
        assertEquals(TaskSignals.NONE, TaskSignals.parse(null));
    }

    @Test
    void degradationGateOnlyFiresWhenEnabledAndDegraded() {
        assertTrue(PlanningLoop.needsPlanApproval(true, true));
        assertFalse(PlanningLoop.needsPlanApproval(false, true));
        assertFalse(PlanningLoop.needsPlanApproval(true, false));
    }

    @Test
    void approvalReplyMustBeShortExplicitAnswer() {
        assertTrue(PlanningLoop.isPlanApprovalReply("开始"));
        assertTrue(PlanningLoop.isPlanApprovalReply("继续"));
        assertTrue(PlanningLoop.isPlanApprovalReply("可以"));
        assertFalse(PlanningLoop.isPlanApprovalReply("开始做另一个任务"));
        assertFalse(PlanningLoop.isPlanApprovalReply("为什么用这个计划？"));
    }

    @Test
    void degradedPlanBlocksUntilExplicitApproval() {
        assertEquals(PlanningLoop.PlanApprovalAction.BLOCK,
                PlanningLoop.planApprovalAction(true, true, false, "整理一下标书"));
        assertEquals(PlanningLoop.PlanApprovalAction.APPROVE,
                PlanningLoop.planApprovalAction(true, true, false, "开始"));
        assertEquals(PlanningLoop.PlanApprovalAction.NONE,
                PlanningLoop.planApprovalAction(true, true, true, "开始"));
        assertEquals(PlanningLoop.PlanApprovalAction.NONE,
                PlanningLoop.planApprovalAction(false, true, false, "开始"));
        assertEquals(PlanningLoop.PlanApprovalAction.NONE,
                PlanningLoop.planApprovalAction(true, false, false, "开始"));
    }

    @Test
    void outerRoundLimitIncludesExactBoundary() {
        assertTrue(PlanningLoop.outerRoundsExhausted(24, 24));
        assertTrue(PlanningLoop.outerRoundsExhausted(25, 24));
        assertFalse(PlanningLoop.outerRoundsExhausted(23, 24));
        assertFalse(PlanningLoop.outerRoundsExhausted(1, 0));
    }
}
