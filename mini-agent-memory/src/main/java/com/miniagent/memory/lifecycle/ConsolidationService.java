package com.miniagent.memory.lifecycle;

/**
 * 记忆巩固服务：从事件流提炼长期记忆。
 */
public interface ConsolidationService {

    /**
     * 巩固指定 session 的记忆。
     * 从 agent_events 中聚合未处理的事件，提炼为 Episode 或 MemoryEntry。
     */
    void consolidate(String sessionId);
}
