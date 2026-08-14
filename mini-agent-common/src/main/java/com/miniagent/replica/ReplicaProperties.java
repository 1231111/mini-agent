package com.miniagent.replica;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 多副本配置：mode=redis 启用 Redis 热存储/跨实例事件/任务并发；mode=local 单机。 */
@Component
@ConfigurationProperties(prefix = "agent.replica")
public class ReplicaProperties {

    private String mode = "local";
    private long todoTtlSeconds = 86400;
    private long memoryTtlSeconds = 86400;
    private long plannerTtlSeconds = 86400;
    private long runLockTtlSeconds = 7200;

    public boolean isRedisMode() {
        return "redis".equalsIgnoreCase(mode);
    }

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public long getTodoTtlSeconds() { return todoTtlSeconds; }
    public void setTodoTtlSeconds(long todoTtlSeconds) { this.todoTtlSeconds = todoTtlSeconds; }
    public long getMemoryTtlSeconds() { return memoryTtlSeconds; }
    public void setMemoryTtlSeconds(long memoryTtlSeconds) { this.memoryTtlSeconds = memoryTtlSeconds; }
    public long getPlannerTtlSeconds() { return plannerTtlSeconds; }
    public void setPlannerTtlSeconds(long plannerTtlSeconds) {
        this.plannerTtlSeconds = plannerTtlSeconds;
    }
    public long getRunLockTtlSeconds() { return runLockTtlSeconds; }
    public void setRunLockTtlSeconds(long runLockTtlSeconds) { this.runLockTtlSeconds = runLockTtlSeconds; }
}
