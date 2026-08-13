package com.miniagent.agent.memory.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "agent_events", indexes = {
    @Index(name = "idx_aevt_session", columnList = "session_id"),
    @Index(name = "idx_aevt_task", columnList = "task_id"),
    @Index(name = "idx_aevt_type", columnList = "event_type"),
    @Index(name = "idx_aevt_processed", columnList = "processed, created_at")
})
public class AgentEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "session_id", length = 128)
    private String sessionId;

    @Column(name = "task_id", length = 128)
    private String taskId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 64)
    private EventType eventType;

    @Column(name = "actor", length = 64)
    private String actor;

    @Lob
    @Column(name = "payload_json", nullable = false, columnDefinition = "LONGTEXT")
    private String payloadJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 32)
    private EventStatus status;

    @Column(name = "processed")
    private Boolean processed = false;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public enum EventType {
        TOOL_EXECUTION, PLAN_CHANGE, ERROR, MEMORY_WRITE,
        USER_FEEDBACK, TASK_START, TASK_COMPLETE, TASK_FAIL
    }

    public enum EventStatus {
        SUCCESS, FAILED
    }

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
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

    public String getPayloadJson() { return payloadJson; }
    public void setPayloadJson(String payloadJson) { this.payloadJson = payloadJson; }

    public EventStatus getStatus() { return status; }
    public void setStatus(EventStatus status) { this.status = status; }

    public Boolean getProcessed() { return processed; }
    public void setProcessed(Boolean processed) { this.processed = processed; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
