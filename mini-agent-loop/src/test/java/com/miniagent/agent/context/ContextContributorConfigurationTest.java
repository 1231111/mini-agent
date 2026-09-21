package com.miniagent.agent.context;

import com.miniagent.agent.task.TaskPlan;
import com.miniagent.agent.task.TaskSignals;
import com.miniagent.application.PromptTemplates;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 点评轮与轻问答轮的提示词注入。
 *
 * <p>守住的是「只改文字，不改资源」：旧实现判出带媒体的追问后会走一条专用通道，
 * 工具面收窄成空表、历史只留 4 条 —— 判定一旦不成立，被砍掉的资源退不回来。
 * 现在媒体只决定 QUESTION 槽里拼哪段文字，工具清单与历史条数由信号和策略另行决定。</p>
 */
class ContextContributorConfigurationTest {

    private final SystemContextContributor contributor =
            new ContextContributorConfiguration().questionModeContributor();

    private static ContextBuildContext ctx(boolean hasMedia, String signals) {
        TaskSignals s = TaskSignals.parse(signals);
        return new ContextBuildContext("s-1", "这不对吧", ContextLoadPolicy.forSignals(s),
                Set.of(), null, TaskPlan.of("测试目标", false, s), hasMedia);
    }

    @Test
    void 带媒体且无动手信号时注入点评提示词() {
        ContextFragment f = contributor.contribute(ctx(true, "question"));
        assertEquals(ContextSlot.QUESTION, f.slot());
        assertEquals(PromptTemplates.REVIEW_MODE_PROMPT, f.text());
    }

    /** 「看下这张图然后发布」这类带媒体但要动手的回合，不能进点评模式。 */
    @Test
    void 带媒体但命中动手信号时不进点评模式() {
        ContextFragment f = contributor.contribute(ctx(true, "publish"));
        assertEquals("", f.text());
    }

    /** 点评轮优先于轻问答轮：两句判据都成立时给出的约束更具体。 */
    @Test
    void 点评轮优先于轻问答轮() {
        ContextBuildContext c = ctx(true, "question");
        assertTrue(c.lightTurn());
        assertTrue(c.reviewTurn());
        assertEquals(PromptTemplates.REVIEW_MODE_PROMPT, contributor.contribute(c).text());
    }

    @Test
    void 不带媒体的纯问答轮走轻问答提示词() {
        ContextFragment f = contributor.contribute(ctx(false, "question"));
        assertEquals(PromptTemplates.QUESTION_MODE, f.text());
    }

    @Test
    void 无任何信号的回合不注入提示词() {
        ContextFragment f = contributor.contribute(ctx(false, "none"));
        assertEquals("", f.text());
    }

    /**
     * 媒体不参与资源判定：带媒体与不带媒体，历史条数与记忆槽必须完全一致。
     */
    @Test
    void 媒体不影响历史条数与工具面() {
        ContextBuildContext without = ctx(false, "question");
        ContextBuildContext with = ctx(true, "question");
        assertEquals(without.policy().historyMaxMessages(), with.policy().historyMaxMessages());
        assertEquals(without.policy().memoryPolicy(), with.policy().memoryPolicy());
        assertFalse(with.policy().suspendActiveTodo());
    }
}
