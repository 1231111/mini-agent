package com.miniagent.memory.writer;

import com.miniagent.memory.model.AgentEvent;
import com.miniagent.memory.model.MemoryEntry;

/**
 * 事件处理器：将 AgentEvent 转化为 MemoryEntry（如果值得记住）。
 */
public interface EventProcessor {

    /**
     * 处理一个事件。返回值：
     * - 非 null：生成了一条记忆
     * - null：该事件不值得记住
     */
    MemoryEntry process(AgentEvent event);
}
