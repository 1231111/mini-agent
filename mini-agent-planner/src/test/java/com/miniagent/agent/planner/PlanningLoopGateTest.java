package com.miniagent.agent.planner;

import com.miniagent.agent.permission.PermissionMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 规划器入口的行为契约。
 *
 * <p>权限模式枚举及降级审批的独立契约。自动语义路由见 PlanningLoopRoutingTest。</p>
 */
class PlanningLoopGateTest {

    @Test
    void defaultModeRetainsItsPermissionMeaning() {
        assertEquals(PermissionMode.DEFAULT, PermissionMode.from("default"));
        assertEquals(PermissionMode.DEFAULT, PermissionMode.from("normal"));
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
