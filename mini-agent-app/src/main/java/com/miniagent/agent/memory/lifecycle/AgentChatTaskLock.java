package com.miniagent.agent.memory.lifecycle;

import com.miniagent.agent.memory.MemoryTaskLock;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class AgentChatTaskLock {
    private final MemoryTaskLock lock;

    public AgentChatTaskLock(MemoryTaskLock lock) {
        this.lock = lock;
    }

    public boolean tryLock(String tenantId, String sessionId, String taskId) {
        return lock.tryLock(key(tenantId, sessionId, taskId), Duration.ofMinutes(9));
    }

    public void unlock(String tenantId, String sessionId, String taskId) {
        lock.unlock(key(tenantId, sessionId, taskId));
    }

    private String key(String tenantId, String sessionId, String taskId) {
        return "episode:" + tenantId + ":" + sessionId + ":" + taskId;
    }
}
