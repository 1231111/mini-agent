package com.miniagent.agent.planner;

import com.miniagent.agent.task.TaskSignals;
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
 */
class PlannerResumePolicyTest {

    /** 十二个信号位，只给测试关心的三位赋值。 */
    private static TaskSignals signals(boolean question, boolean taskAction, boolean continueTask) {
        return new TaskSignals(false, false, false, false, false, false, false,
                question, false, taskAction, continueTask, false);
    }

    /** 动手轮：既不是纯问答，也没有说「继续」。 */
    private static final TaskSignals ACTION = signals(false, true, false);
    /** 纯问答轮 */
    private static final TaskSignals QUESTION = signals(true, false, false);
    /** 明说继续 */
    private static final TaskSignals CONTINUE = signals(false, true, true);

    @Test
    void 图已全部终态就不接管() {
        assertFalse(PlannerResumePolicy.shouldResume(CONTINUE, "继续", true, false));
    }

    @Test
    void 纯问答轮不接管旧图() {
        assertFalse(PlannerResumePolicy.shouldResume(QUESTION, "你好", false, true));
    }

    @Test
    void 明说继续就接管() {
        assertTrue(PlannerResumePolicy.shouldResume(CONTINUE, "继续", false, true));
        assertTrue(PlannerResumePolicy.shouldResume(ACTION, "继续", false, true));
        assertTrue(PlannerResumePolicy.shouldResume(ACTION, "接着做", false, true));
    }

    @Test
    void 正在等问题时短答复可以接管() {
        assertTrue(PlannerResumePolicy.shouldResume(ACTION, "好的", true, true));
        assertTrue(PlannerResumePolicy.shouldResume(ACTION, "已提供密钥 sk-xxx", true, true));
    }

    @Test
    void 没在等问题时短答复不接管_这是被删掉的那条污染通路() {
        // 旧实现：会话里曾经等过用户 → resumeRequested 恒真 → 这句「好的」会接管旧图。
        // 现在接管前提是「此刻有悬而未决的问题」，历史上等过不算。
        assertFalse(PlannerResumePolicy.shouldResume(ACTION, "好的", false, true));
        assertFalse(PlannerResumePolicy.shouldResume(ACTION, "帮我看看这段日志", false, true));
    }

    @Test
    void 正在等问题但消息明显是新问题_同样不接管() {
        assertFalse(PlannerResumePolicy.shouldResume(ACTION, "这个怎么报错了", true, true));
        assertFalse(PlannerResumePolicy.shouldResume(ACTION, "帮我把代码改一下", true, true));
    }

    @Test
    void 页面批准消息属于明确续跑_不依赖等确认状态() {
        // MiniAgentChatPageController 在用户点「批准」时往会话里追加的正是这类消息
        assertTrue(PlannerResumePolicy.shouldResume(
                ACTION, "用户已批准工具 exec_command，请继续。", false, true));
        assertTrue(PlannerResumePolicy.shouldResume(
                ACTION, "用户已批准 Plan，请按 todo 开始执行写操作与交付。", false, true));
    }
}
