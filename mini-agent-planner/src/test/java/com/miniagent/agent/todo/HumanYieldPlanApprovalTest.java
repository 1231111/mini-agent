package com.miniagent.agent.todo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 计划审批只接受明确的短答复。
 *
 * <p>如果放宽成「包含关键词」，用户一句「开始做另一个任务」就会把上一张规则图放行，
 * 这正是审批卡点最不该出现的漏洞。</p>
 */
class HumanYieldPlanApprovalTest {

    @Test
    void acceptsExplicitShortApprovals() {
        assertTrue(HumanYield.looksLikePlanApproval("开始"));
        assertTrue(HumanYield.looksLikePlanApproval("继续"));
        assertTrue(HumanYield.looksLikePlanApproval("执行"));
        assertTrue(HumanYield.looksLikePlanApproval("可以。"));
        assertTrue(HumanYield.looksLikePlanApproval("批准！"));
        assertTrue(HumanYield.looksLikePlanApproval("按这个来"));
        assertTrue(HumanYield.looksLikePlanApproval("confirm"));
    }

    @Test
    void rejectsSentencesThatOnlyContainApprovalWords() {
        assertFalse(HumanYield.looksLikePlanApproval("开始做另一个任务"));
        assertFalse(HumanYield.looksLikePlanApproval("开始生成标书并保存"));
        assertFalse(HumanYield.looksLikePlanApproval("为什么要执行这个计划？"));
        assertFalse(HumanYield.looksLikePlanApproval(""));
        assertFalse(HumanYield.looksLikePlanApproval(null));
    }
}
