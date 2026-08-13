package com.miniagent.agent.memory.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "agent_episodes", indexes = {
    @Index(name = "idx_ae_tenant", columnList = "tenant_id"),
    @Index(name = "idx_ae_project", columnList = "project_id"),
    @Index(name = "idx_ae_outcome", columnList = "outcome")
})
public class AgentEpisodeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "user_id", length = 64)
    private String userId;

    @Column(name = "project_id", length = 128)
    private String projectId;

    @Column(name = "session_id", length = 128)
    private String sessionId;

    @Column(name = "task_summary", nullable = false, length = 500)
    private String taskSummary;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 32)
    private Outcome outcome;

    @Column(name = "failure_code", length = 128)
    private String failureCode;

    @Lob
    @Column(name = "actions_json", nullable = false, columnDefinition = "LONGTEXT")
    private String actionsJson;

    @Lob
    @Column(name = "observations_json", columnDefinition = "TEXT")
    private String observationsJson;

    @Lob
    @Column(name = "resolution", columnDefinition = "TEXT")
    private String resolution;

    @Column(name = "importance")
    private Double importance = 0.5;

    @Column(name = "access_count")
    private Integer accessCount = 0;

    @Column(name = "last_accessed_at")
    private LocalDateTime lastAccessedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public enum Outcome {
        SUCCESS, FAILURE, PARTIAL
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

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getTaskSummary() { return taskSummary; }
    public void setTaskSummary(String taskSummary) { this.taskSummary = taskSummary; }

    public Outcome getOutcome() { return outcome; }
    public void setOutcome(Outcome outcome) { this.outcome = outcome; }

    public String getFailureCode() { return failureCode; }
    public void setFailureCode(String failureCode) { this.failureCode = failureCode; }

    public String getActionsJson() { return actionsJson; }
    public void setActionsJson(String actionsJson) { this.actionsJson = actionsJson; }

    public String getObservationsJson() { return observationsJson; }
    public void setObservationsJson(String observationsJson) { this.observationsJson = observationsJson; }

    public String getResolution() { return resolution; }
    public void setResolution(String resolution) { this.resolution = resolution; }

    public Double getImportance() { return importance; }
    public void setImportance(Double importance) { this.importance = importance; }

    public Integer getAccessCount() { return accessCount; }
    public void setAccessCount(Integer accessCount) { this.accessCount = accessCount; }

    public LocalDateTime getLastAccessedAt() { return lastAccessedAt; }
    public void setLastAccessedAt(LocalDateTime lastAccessedAt) { this.lastAccessedAt = lastAccessedAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
