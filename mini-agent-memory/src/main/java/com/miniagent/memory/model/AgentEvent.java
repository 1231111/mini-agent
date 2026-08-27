package com.miniagent.memory.model;

import java.util.Map;

/**
 * Agent 事件。所有 Agent 行为先进入 Event，由 Memory 系统决定哪些值得记住。
 */
public class AgentEvent {
    private Long id;
    private String tenantId;
    private String sessionId;
    private String taskId;
    private EventType eventType;
    private String actor;           // executor / planner / user / system
    private Map<String, Object> payload;
    private EventStatus status;
    private boolean processed;
    private long createdAt;

    public AgentEvent() {
        this.createdAt = System.currentTimeMillis();
    }

    public enum EventType {
        TOOL_EXECUTION,
        PLAN_CHANGE,
        ERROR,
        MEMORY_WRITE,
        USER_FEEDBACK,
        TASK_START,
        TASK_COMPLETE,
        TASK_FAIL
    }

    public enum EventStatus {
        SUCCESS, FAILED
    }

    // --- getters/setters ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }

    public EventType getEventType() { return eventType; }
    public void setEventType(EventType eventType) { this.eventType = eventType; }

    public String getActor() { return actor; }
    public void setActor(String actor) { this.actor = actor; }

    public Map<String, Object> getPayload() { return payload; }
    public void setPayload(Map<String, Object> payload) { this.payload = payload; }

    public EventStatus getStatus() { return status; }
    public void setStatus(EventStatus status) { this.status = status; }

    public boolean isProcessed() { return processed; }
    public void setProcessed(boolean processed) { this.processed = processed; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
}
