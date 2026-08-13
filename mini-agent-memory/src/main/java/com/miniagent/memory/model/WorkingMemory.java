package com.miniagent.memory.model;

import java.util.*;

/**
 * 工作记忆：当前任务的实时状态。
 */
public class WorkingMemory {
    private String sessionId;
    private String tenantId;
    private String userId;
    private String projectId;
    private String goal;
    private String planId;
    private String currentTaskId;
    private List<String> completedTasks;
    private List<String> failedTasks;
    private Map<String, Object> variables;
    private Map<String, Object> constraints;
    private Map<String, Object> artifacts;
    private String status;
    private long updatedAt;

    public WorkingMemory() {
        this.completedTasks = new ArrayList<>();
        this.failedTasks = new ArrayList<>();
        this.variables = new LinkedHashMap<>();
        this.constraints = new LinkedHashMap<>();
        this.artifacts = new LinkedHashMap<>();
        this.status = "ACTIVE";
        this.updatedAt = System.currentTimeMillis();
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

    public List<String> getCompletedTasks() { return completedTasks; }
    public void setCompletedTasks(List<String> completedTasks) { this.completedTasks = completedTasks; }
    public void addCompletedTask(String taskId) { this.completedTasks.add(taskId); }

    public List<String> getFailedTasks() { return failedTasks; }
    public void setFailedTasks(List<String> failedTasks) { this.failedTasks = failedTasks; }
    public void addFailedTask(String taskId) { this.failedTasks.add(taskId); }

    public Map<String, Object> getVariables() { return variables; }
    public void setVariables(Map<String, Object> variables) { this.variables = variables; }
    public void setVariable(String key, Object value) { this.variables.put(key, value); }

    public Map<String, Object> getConstraints() { return constraints; }
    public void setConstraints(Map<String, Object> constraints) { this.constraints = constraints; }
    public void setConstraint(String key, Object value) { this.constraints.put(key, value); }

    public Map<String, Object> getArtifacts() { return artifacts; }
    public void setArtifacts(Map<String, Object> artifacts) { this.artifacts = artifacts; }
    public void setArtifact(String key, Object value) { this.artifacts.put(key, value); }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }
}
