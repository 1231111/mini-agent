package com.miniagent.agent.memory.writer;

import com.miniagent.agent.tool.ToolStatus;
import com.miniagent.memory.MemoryManager;
import com.miniagent.memory.model.AgentEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 工具执行事实必须真的落成事件。
 *
 * <p>这个类是整条记忆链路上 {@code TOOL_EXECUTION} 的唯一生产者。它不写，
 * 下游三处全部读到空集 —— 表现是日志里「执行了 0 个工具调用，0 个失败」，
 * 而实际那一轮跑了 4 次工具。所以这里守的主要不是格式，而是「事件真的产生了」。</p>
 *
 * <p>另一条不变量：{@code error} 与 {@code result} 只能有一个。两者同时出现会被
 * {@code DefaultEventProcessor.buildContent} 渲染两遍同一段文本；
 * 且失败写 {@code error} 才能命中重要度评估器的 +0.15。</p>
 */
class MemoryToolExecutionSinkTest {

    private final MemoryManager memoryManager = mock(MemoryManager.class);
    private final MemoryToolExecutionSink sink = new MemoryToolExecutionSink();

    MemoryToolExecutionSinkTest() {
        ReflectionTestUtils.setField(sink, "memoryManager", memoryManager);
    }

    @Test
    void successIsRecordedWithResultOnly() {
        sink.onToolExecuted("s1", 3, "write_file", ToolStatus.SUCCESS, "{\"path\":\"a.txt\"}");

        AgentEvent event = lastEvent();
        assertEquals(AgentEvent.EventType.TOOL_EXECUTION, event.getEventType());
        assertEquals(AgentEvent.EventStatus.SUCCESS, event.getStatus());
        assertEquals("executor", event.getActor());
        assertEquals("s1", event.getSessionId());
        assertEquals("write_file", event.getPayload().get("tool"));
        assertEquals("{\"path\":\"a.txt\"}", event.getPayload().get("result"));
        assertFalse(event.getPayload().containsKey("error"));
    }

    @Test
    void failureIsRecordedWithErrorOnly() {
        sink.onToolExecuted("s1", 4, "exec_command", ToolStatus.FAILED, "命令执行失败");

        AgentEvent event = lastEvent();
        assertEquals(AgentEvent.EventStatus.FAILED, event.getStatus());
        assertEquals("命令执行失败", event.getPayload().get("error"));
        assertFalse(event.getPayload().containsKey("result"));
    }

    /** 超时与取消都没有成功，都要计入失败，否则失败率被低估。 */
    @Test
    void timeoutAndCancelledCountAsFailed() {
        sink.onToolExecuted("s1", 5, "web_search", ToolStatus.TIMEOUT, "超时");
        sink.onToolExecuted("s1", 5, "web_search", ToolStatus.CANCELLED, "已取消");

        ArgumentCaptor<AgentEvent> captor = ArgumentCaptor.forClass(AgentEvent.class);
        verify(memoryManager, times(2)).recordEvent(captor.capture());
        assertTrue(captor.getAllValues().stream()
                .allMatch(e -> e.getStatus() == AgentEvent.EventStatus.FAILED));
    }

    /** 记忆是旁路：上报失败不能把正在跑的任务带崩。 */
    @Test
    void missingMemoryManagerIsSilentlyIgnored() {
        MemoryToolExecutionSink bare = new MemoryToolExecutionSink();
        assertDoesNotThrow(() ->
                bare.onToolExecuted("s1", 1, "todo", ToolStatus.SUCCESS, "{}"));
    }

    private AgentEvent lastEvent() {
        ArgumentCaptor<AgentEvent> captor = ArgumentCaptor.forClass(AgentEvent.class);
        verify(memoryManager).recordEvent(captor.capture());
        AgentEvent event = captor.getValue();
        assertNotNull(event, "工具执行必须产生事件，否则记忆链路整条失真");
        return event;
    }
}
