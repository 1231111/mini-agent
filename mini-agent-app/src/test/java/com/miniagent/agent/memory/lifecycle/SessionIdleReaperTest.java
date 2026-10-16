package com.miniagent.agent.memory.lifecycle;

import com.miniagent.agent.memory.ChatMemoryConfig;
import com.miniagent.application.ChatStreamingService;
import com.miniagent.common.event.SessionEvictedEvent;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 空闲会话回收的三条约束：按 TTL 判、不在跑才回收、单轮有上限。
 *
 * <p>最容易写错的一条是「正在跑的会话不该被摘掉」—— 记忆窗口的取用时间只在取窗口那一刻刷新，
 * 一次长任务完全可能超过 TTL，若不加这一道，回收会跟正在执行的一轮抢内存。</p>
 */
class SessionIdleReaperTest {

    private static final long TTL = 60_000L;

    private ChatMemoryConfig chatMemoryConfig;
    private ChatStreamingService chatStreamingService;
    private SessionIdleReaper reaper;
    private final List<String> evicted = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        chatMemoryConfig = new ChatMemoryConfig();
        chatStreamingService = Mockito.mock(ChatStreamingService.class);
        reaper = new SessionIdleReaper();
        evicted.clear();

        ApplicationEventPublisher collector = event -> {
            if (event instanceof SessionEvictedEvent e) {
                evicted.add(e.sessionId());
                assertFalse(e.isDeleted(), "空闲回收发的是 IDLE，不是 DELETED");
            }
        };
        set(reaper, "chatMemoryConfig", chatMemoryConfig);
        set(reaper, "chatStreamingService", chatStreamingService);
        set(reaper, "eventPublisher", collector);
        set(reaper, "idleTtlMs", TTL);
        set(reaper, "batchSize", 100);
    }

    @Test
    void reapsIdleButSkipsRunningAndFreshSessions() throws Exception {
        long now = System.currentTimeMillis();
        seedMemory("idle-old", now - TTL - 1);
        seedMemory("fresh", now);
        seedMemory("running", now - TTL - 10);
        Mockito.when(chatStreamingService.isTaskRunning("running")).thenReturn(true);

        reaper.reapIdleSessions();

        assertTrue(evicted.contains("idle-old"), "超过 TTL 且没在跑的应被回收: " + evicted);
        assertFalse(evicted.contains("fresh"), "刚用过的不能回收: " + evicted);
        assertFalse(evicted.contains("running"), "正在执行的一轮不能被摘掉内存: " + evicted);
        assertEquals(1, evicted.size(), "只应回收那一个: " + evicted);
    }

    @Test
    void sessionWithNoAccessRecordCountsAsIdle() throws Exception {
        // 只有 memories 里有、lastAccess 里没有：说明取用时间没记上，按"从未取用"处理
        seedMemory("orphan", null);

        reaper.reapIdleSessions();

        assertTrue(evicted.contains("orphan"), "没有活动记录的会话按空闲处理: " + evicted);
    }

    @Test
    void batchSizeCapsOneRound() throws Exception {
        long now = System.currentTimeMillis();
        for (int i = 0; i < 10; i++) {
            seedMemory("s" + i, now - TTL - 1);
        }
        set(reaper, "batchSize", 3);

        reaper.reapIdleSessions();

        assertEquals(3, evicted.size(), "单轮回收数量必须被上限截住，否则一次扫描可能卡死: " + evicted);
    }

    @Test
    void nonPositiveTtlTurnsTheReaperOff() throws Exception {
        seedMemory("s1", System.currentTimeMillis() - TTL * 10);
        set(reaper, "idleTtlMs", 0L);

        reaper.reapIdleSessions();

        assertTrue(evicted.isEmpty(), "TTL 配成 0 应当显式关掉这个后台动作: " + evicted);
    }

    @Test
    void reaperWorksWithoutStreamingServicePresent() throws Exception {
        // 装配里没有 SSE 组件时不能每轮空指针，退化成"不跳过"
        set(reaper, "chatStreamingService", null);
        seedMemory("s1", System.currentTimeMillis() - TTL - 1);

        reaper.reapIdleSessions();

        assertTrue(evicted.contains("s1"), "缺探测口时应降级为照常回收: " + evicted);
    }

    /** 往 ChatMemoryConfig 的私有映射里放一个会话（可选地写一个最后取用时间）。 */
    @SuppressWarnings("unchecked")
    private void seedMemory(String sessionId, Long lastAccessAt) throws Exception {
        Field memoriesField = ChatMemoryConfig.class.getDeclaredField("memories");
        memoriesField.setAccessible(true);
        Map<String, ChatMemory> memories = (Map<String, ChatMemory>) memoriesField.get(chatMemoryConfig);
        memories.put(sessionId, MessageWindowChatMemory.withMaxMessages(2));

        if (lastAccessAt != null) {
            Field lastAccessField = ChatMemoryConfig.class.getDeclaredField("lastAccess");
            lastAccessField.setAccessible(true);
            Map<String, Long> lastAccess = (Map<String, Long>) lastAccessField.get(chatMemoryConfig);
            lastAccess.put(sessionId, lastAccessAt);
        }
    }

    private static void set(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
