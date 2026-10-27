package com.miniagent.agent.context;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 本轮上下文加载策略：判据只有「会话里有没有挂起的 todo 清单」。
 *
 * <p>原来这里按命中的文本词表分三档（轻问答 / 续任务 / 动手），
 * 词表层删除后收敛成两档。这些用例守住两件事：
 * 续跑必须把清单捞回来，新任务必须释放旧清单；
 * 任何一档都不许把历史或长期记忆砍到不可恢复。</p>
 */
class ContextLoadPolicyTest {

    @Test
    void 有挂起清单时续跑并恢复() {
        ContextLoadPolicy p = ContextLoadPolicy.forSuspendedTodo(true);
        assertTrue(p.resumeSuspendedTodo());
        assertFalse(p.suspendActiveTodo());
        assertTrue(p.injectTodo());
        assertTrue(p.injectSkills());
        assertTrue(p.memoryPolicy().working());
    }

    @Test
    void 没有挂起清单时释放旧计划() {
        ContextLoadPolicy p = ContextLoadPolicy.forSuspendedTodo(false);
        assertTrue(p.suspendActiveTodo());
        assertFalse(p.resumeSuspendedTodo());
        assertTrue(p.injectTodo());
        assertTrue(p.memoryPolicy().working());
    }

    /** 不再有「轻问答只带摘要」这一档：任何一轮都不砍长期记忆。 */
    @Test
    void 两档都开启长期记忆() {
        assertTrue(ContextLoadPolicy.forSuspendedTodo(false).memoryPolicy().longTerm());
        assertTrue(ContextLoadPolicy.forSuspendedTodo(true).memoryPolicy().longTerm());
    }

    /**
     * 历史兜底条数不能是 0。
     *
     * <p>0 会让 {@code selectHistory} 直接返回空，模型看不到上一轮在做什么，
     * 跨轮追问必然答错。这条下限必须由策略本身守住，不能只靠配置。</p>
     */
    @Test
    void 历史兜底条数不为零() {
        assertNotEquals(0, ContextLoadPolicy.forSuspendedTodo(false).historyMaxMessages());
        assertNotEquals(0, ContextLoadPolicy.forSuspendedTodo(true).historyMaxMessages());
    }
}
