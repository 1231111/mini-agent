package com.miniagent.agent.context;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 上下文加载策略现在只回答「加载多少历史、开哪些注入通道」。
 *
 * <p>「挂起 / 恢复任务清单」已经移出本类。旧实现按「会话里有没有被挂起的清单」
 * 分 CONTINUE / ACTION 两档，那个条件一旦成立就恒成立，于是每一轮都把旧清单捞回来，
 * 新任务被上一个任务的计划接管。清单归属现在由 {@code TaskTodoStore} 与
 * {@code TaskScopeRegistry} 管，本类不再参与。</p>
 */
class ContextLoadPolicyTest {

    @Test
    void 默认策略全开且不裁剪历史() {
        ContextLoadPolicy p = ContextLoadPolicy.defaults();
        assertTrue(p.injectTodo());
        assertTrue(p.injectMemory());
        assertTrue(p.injectUser());
        assertTrue(p.injectSkills());
        assertTrue(p.memoryPolicy().working(), "工作记忆跟随 todo 通道");
        assertTrue(p.memoryPolicy().longTerm(), "长期记忆默认开启，不按文本降档");
        assertEquals(-1, p.historyMaxMessages(), "-1 = 不裁剪");
    }

    /** 覆盖历史条数不该顺手改掉其它通道（旧实现里 withXxx 最容易漏字段）。 */
    @Test
    void 覆盖历史条数不影响其它通道() {
        ContextLoadPolicy p = ContextLoadPolicy.defaults().withHistoryMaxMessages(8);
        assertEquals(8, p.historyMaxMessages());
        assertTrue(p.injectTodo());
        assertTrue(p.injectMemory());
        assertTrue(p.injectUser());
        assertTrue(p.injectSkills());
    }

    @Test
    void 覆盖记忆开关不影响历史条数() {
        ContextLoadPolicy p = ContextLoadPolicy.defaults().withInjectMemory(false);
        assertFalse(p.injectMemory());
        assertEquals(-1, p.historyMaxMessages());
        assertTrue(p.injectTodo());
    }

    /**
     * 历史兜底条数不能是 0。
     *
     * <p>0 会让 {@code selectHistory} 直接返回空，模型看不到上一轮在做什么，
     * 跨轮追问必然答错。这条下限必须由策略本身守住，不能只靠配置。</p>
     */
    @Test
    void 历史兜底条数不为零() {
        assertNotEquals(0, ContextLoadPolicy.defaults().historyMaxMessages());
    }
}
