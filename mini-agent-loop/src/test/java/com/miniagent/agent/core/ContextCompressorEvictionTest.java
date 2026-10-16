package com.miniagent.agent.core;

import com.miniagent.common.event.SessionEvictedEvent;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 压缩状态的会话级回收。
 *
 * <p>背景：{@code sessionStates} 的键是<b>任务作用域键</b>（{@code sessionId} 或
 * {@code sessionId#N}），而既有的 {@code clearSession(sessionId)} 只删「当前任务」那一个键 ——
 * 切过任务的会话会剩下 {@code sessionId#1}、{@code sessionId#2} 常驻，
 * 桌面客户端进程不退出就一直留着。这些用例锁住"按会话整片回收"，
 * 同时钉死"不能误伤只共享前缀的另一个会话"。</p>
 */
class ContextCompressorEvictionTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> states(ContextCompressor compressor) throws ReflectiveOperationException {
        Field field = ContextCompressor.class.getDeclaredField("sessionStates");
        field.setAccessible(true);
        return (Map<String, Object>) field.get(compressor);
    }

    /** 往私有状态表里塞一个指定作用域键的条目（SessionState 是私有静态内部类）。 */
    private static void seed(ContextCompressor compressor, String scopeKey) throws ReflectiveOperationException {
        Class<?> stateType = Class.forName("com.miniagent.agent.core.ContextCompressor$SessionState");
        Constructor<?> ctor = stateType.getDeclaredConstructor();
        ctor.setAccessible(true);
        states(compressor).put(scopeKey, ctor.newInstance());
    }

    @Test
    void evictSessionRemovesAllTaskScopesOfThatSession() throws Exception {
        ContextCompressor compressor = new ContextCompressor();
        seed(compressor, "s1");
        seed(compressor, "s1#1");
        seed(compressor, "s1#2");
        seed(compressor, "s2");
        // 前缀相同的另一个会话：回收 s1 时绝不能顺手删掉它
        seed(compressor, "s1-other");

        int before = states(compressor).size();
        assertEquals(5, before, "前置条件：五个条目都已就位");

        compressor.evictSession("s1");

        Map<String, Object> left = states(compressor);
        assertFalse(left.containsKey("s1"), "会话自身的状态要清掉");
        assertFalse(left.containsKey("s1#1"), "旧任务作用域的状态也要清掉（这正是 clearSession 漏掉的那部分）");
        assertFalse(left.containsKey("s1#2"), "旧任务作用域的状态也要清掉");
        assertTrue(left.containsKey("s2"), "别的会话的状态不能被误删");
        assertTrue(left.containsKey("s1-other"), "只共享前缀的不同会话不能被误删");
    }

    @Test
    void idleEventAlsoReclaimsCompressionState() throws Exception {
        ContextCompressor compressor = new ContextCompressor();
        seed(compressor, "s9#3");

        compressor.onSessionEvicted(SessionEvictedEvent.idle("s9"));

        assertFalse(states(compressor).containsKey("s9#3"),
                "空闲会话的压缩状态同样要回收（进程不退出时它是净增长）");
    }

    @Test
    void evictSessionToleratesBlankInput() throws Exception {
        ContextCompressor compressor = new ContextCompressor();
        seed(compressor, "s1");

        compressor.evictSession(null);
        compressor.evictSession("  ");

        assertEquals(1, states(compressor).size(), "空参数不应清掉任何东西");
    }
}
