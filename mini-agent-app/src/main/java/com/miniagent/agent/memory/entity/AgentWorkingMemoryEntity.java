package com.miniagent.agent.memory.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "agent_working_memories", indexes = {
    @Index(name = "idx_awm_tenant", columnList = "tenant_id"),
    @Index(name = "idx_awm_status", columnList = "status")
})
public class AgentWorkingMemoryEntity {

    @Id
    @Column(name = "session_id", nullable = false, length = 128)
    private String sessionId;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "user_id", length = 64)
    private String userId;

    @Column(name = "project_id", length = 128)
    private String projectId;

    @Lob
    @Column(name = "goal", columnDefinition = "TEXT")
    private String goal;

    @Column(name = "plan_id", length = 128)
    private String planId;

    @Column(name = "current_task_id", length = 128)
    private String currentTaskId;

    @Lob
    @Column(name = "completed_tasks_json", columnDefinition = "TEXT")
    private String completedTasksJson;

    @Lob
    @Column(name = "failed_tasks_json", columnDefinition = "TEXT")
    private String failedTasksJson;

    @Lob
    @Column(name = "variables_json", columnDefinition = "TEXT")
    private String variablesJson;

    @Lob
    @Column(name = "constraints_json", columnDefinition = "TEXT")
    private String constraintsJson;

    @Lob
    @Column(name = "artifacts_json", columnDefinition = "TEXT")
    private String artifactsJson;

    @Column(name = "status", length = 32)
    private String status = "ACTIVE";

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // --- getters/setters ---

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }

    public String getGoal() { return goal; }
    public void setGoal(String goal) { this.goal = goal; }

    public String getPlanId() { return planId; }
    public void setPlanId(String planId) { this.planId = planId; }

    public String getCurrentTaskId() { return currentTaskId; }
    public void setCurrentTaskId(String currentTaskId) { this.currentTaskId = currentTaskId; }

    public String getCompletedTasksJson() { return completedTasksJson; }
    public void setCompletedTasksJson(String completedTasksJson) { this.completedTasksJson = completedTasksJson; }

    public String getFailedTasksJson() { return failedTasksJson; }
    public void setFailedTasksJson(String failedTasksJson) { this.failedTasksJson = failedTasksJson; }

    public String getVariablesJson() { return variablesJson; }
    public void setVariablesJson(String variablesJson) { this.variablesJson = variablesJson; }

    public String getConstraintsJson() { return constraintsJson; }
    public void setConstraintsJson(String constraintsJson) { this.constraintsJson = constraintsJson; }

    public String getArtifactsJson() { return artifactsJson; }
    public void setArtifactsJson(String artifactsJson) { this.artifactsJson = artifactsJson; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
