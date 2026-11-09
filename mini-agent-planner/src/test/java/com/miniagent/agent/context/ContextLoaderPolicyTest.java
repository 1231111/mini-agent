package com.miniagent.agent.context;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ContextLoader} 在默认策略之上按指代做的微调。
 *
 * <p>指代只影响<b>历史条数</b>：命中指代时按相关度精捞，而不是盲目取最近 N 条。
 * 它不再影响任务边界，也不再决定要不要动 todo 清单 —— 那是旧实现的问题来源。</p>
 */
class ContextLoaderPolicyTest {

    @Test
    void parsesTaskIdOnlyFromTheOwningSessionScopeKey() {
        assertEquals(0L, ContextLoader.parseTaskId("session-1", "session-1"));
        assertEquals(12L, ContextLoader.parseTaskId("session-1#12", "session-1"));
        assertThrows(IllegalArgumentException.class,
                () -> ContextLoader.parseTaskId("session-2#12", "session-1"));
        assertThrows(IllegalArgumentException.class,
                () -> ContextLoader.parseTaskId("session-1#0", "session-1"));
    }


    private final ContextLoader loader = new ContextLoader();

    /** 无指代：原样用默认策略，四个通道全开。 */
    @Test
    void 无指代时用默认策略() {
        ContextLoadPolicy p = loader.resolvePolicy(ContextReferenceDecision.none());
        assertEquals(ContextLoadPolicy.defaults(), p);
        assertTrue(p.injectMemory());
        assertTrue(p.injectUser());
    }

    /** 有指代：只为「按相关度精捞历史」改历史条数，其它通道不变。 */
    @Test
    void 有指代只覆盖历史条数() {
        ContextReferenceDecision ref =
                new ContextReferenceDecision(true, 0.9, List.of("那份报告"), false);
        ContextLoadPolicy p = loader.resolvePolicy(ref);
        assertNotEquals(ContextLoadPolicy.defaults().historyMaxMessages(),
                p.historyMaxMessages(), "指代场景要换成精捞上限");
        assertTrue(p.injectTodo());
        assertTrue(p.injectUser());
        assertTrue(p.injectSkills());
    }

    /** 否定指代（「别参考上面」）不加载旧历史。 */
    @Test
    void 否定指代不触发历史精捞() {
        ContextReferenceDecision ref =
                new ContextReferenceDecision(true, 0.2, List.of("上面"), true);
        assertFalse(ref.shouldLoadPriorHistory());
        assertEquals(ContextLoadPolicy.defaults(), loader.resolvePolicy(ref));
    }
}
