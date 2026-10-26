package com.miniagent.agent.planner;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Planner 接管判据：什么情况下可以把本轮交给上一轮留下的规划图。
 *
 * <p>这个测试存在的首要目的是把「历史任务污染当前任务」那条通路钉死：
 * <b>没在等用户答复时，一句短话不许接管旧图。</b>
 * 旧实现用 {@code PlannerStateStore} 里一个永不失效的进程内标记
 * （一旦系统在该会话等过用户，此后任何短消息都算答复）作判据，
 * 于是用户开始做新事情时会被旧图的节点调度接管。</p>
 *
 * <p>判据只有两个：图当前是否在等人（{@code awaitingConfirm} /
 * {@code awaitingPlanApproval}），以及这句话本身是不是续跑/授权语句。
 * 任务信号层已删除，这里不再需要构造「命中哪些词」的输入。</p>
 */
class PlannerResumePolicyTest {

    @Test
    void 图已全部终态就不接管() {
        assertFalse(PlannerResumePolicy.shouldResume("继续", true, false));
    }

    @Test
    void 纯寒暄且没在等人时不接管旧图() {
        assertFalse(PlannerResumePolicy.shouldResume("你好", false, true));
    }

    @Test
    void 明说继续就接管() {
        assertTrue(PlannerResumePolicy.shouldResume("继续", false, true));
        assertTrue(PlannerResumePolicy.shouldResume("接着做", false, true));
    }

    @Test
    void 正在等问题时短答复可以接管() {
        assertTrue(PlannerResumePolicy.shouldResume("好的", true, true));
        assertTrue(PlannerResumePolicy.shouldResume("已提供密钥 sk-xxx", true, true));
    }

    @Test
    void 计划待审批时明确批准可以接管() {
        assertTrue(PlannerResumePolicy.shouldResume("开始", false, true, true));
        assertTrue(PlannerResumePolicy.shouldResume("批准", false, true, true));
        assertTrue(PlannerResumePolicy.shouldResume("按这个来", false, true, true));
    }

    @Test
    void 计划待审批时带任务词的句子不算批准() {
        assertFalse(PlannerResumePolicy.shouldResume("开始做另一个任务", false, true, true));
        assertFalse(PlannerResumePolicy.shouldResume("开始生成标书", false, true, true));
    }

    @Test
    void 没有待审批计划时_开始_不接管旧图() {
        assertFalse(PlannerResumePolicy.shouldResume("开始", false, true, false));
    }

    @Test
    void 没在等问题时短答复不接管_这是被删掉的那条污染通路() {
        // 旧实现：会话里曾经等过用户 → resumeRequested 恒真 → 这句「好的」会接管旧图。
        // 现在接管前提是「此刻有悬而未决的问题」，历史上等过不算。
        assertFalse(PlannerResumePolicy.shouldResume("好的", false, true));
        assertFalse(PlannerResumePolicy.shouldResume("帮我看看这段日志", false, true));
    }

    @Test
    void 正在等问题但消息明显是新问题_同样不接管() {
        assertFalse(PlannerResumePolicy.shouldResume("这个怎么报错了", true, true));
        assertFalse(PlannerResumePolicy.shouldResume("帮我把代码改一下", true, true));
    }

    @Test
    void 页面批准消息属于明确续跑_不依赖等确认状态() {
        // MiniAgentChatPageController 在用户点「批准」时往会话里追加的正是这类消息
        assertTrue(PlannerResumePolicy.shouldResume(
                "用户已批准工具 exec_command，请继续。", false, true));
        assertTrue(PlannerResumePolicy.shouldResume(
                "用户已批准 Plan，请按 todo 开始执行写操作与交付。", false, true));
    }
}
