package com.miniagent.agent.context;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ContextLoader} 在基础策略之上按指代做的微调。
 *
 * <p>指代精捞只在新任务上生效：续跑轮本来就要看完整历史，
 * 按相关度裁剪反而会丢掉刚做过的步骤。</p>
 */
class ContextLoaderPolicyTest {

    private final ContextLoader loader = new ContextLoader();

    /**
     * 新任务无指代：长期记忆照常注入。
     *
     * <p>删掉词表层之前，短问答走 LIGHT 策略（{@code injectMemory=false}）。
     * 现在区分「短问题」与「动手任务」不能再靠用户文本，策略里只剩「有没有挂起的 todo」，
     * 所以短问答也会注入长期记忆。这是删除分类层后的既定结果：多花一点 token，
     * 换「不再按字面猜用户想干什么」。{@code injectMidterm} 仍恒为 false ——
     * 那条通路没有生产者。</p>
     */
    @Test
    void 新任务无指代也注入长期记忆但中期恒关() {
        ContextLoadPolicy p = loader.resolvePolicy(false, ContextReferenceDecision.none());
        assertTrue(p.injectMemory(), "ACTION 策略 injectMemory 一直为 true，不按文本降档");
        assertFalse(p.injectMidterm(), "中期记忆没有生产者，恒不注入");
        assertTrue(p.injectUser());
        assertTrue(p.suspendActiveTodo(), "新任务要释放上一任务的活动清单");
        assertFalse(p.resumeSuspendedTodo(), "没有挂起清单可捞");
    }

    @Test
    void 新任务有指代打开长期记忆() {
        ContextReferenceDecision ref =
                new ContextReferenceDecision(true, 0.9, List.of("那份报告"), false);
        ContextLoadPolicy p = loader.resolvePolicy(false, ref);
        assertTrue(p.injectMemory());
        assertFalse(p.injectMidterm());
    }

    /** 续跑轮不受指代影响：本来就要看完整历史。 */
    @Test
    void 续跑轮的指代不改变策略() {
        ContextLoadPolicy plain = loader.resolvePolicy(
                true, ContextReferenceDecision.none());
        ContextLoadPolicy withRef = loader.resolvePolicy(
                true, new ContextReferenceDecision(true, 0.9, List.of("那个文件"), false));
        assertEquals(plain, withRef);
    }
}
