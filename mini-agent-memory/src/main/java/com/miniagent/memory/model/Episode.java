package com.miniagent.memory.model;

import java.util.*;

/**
 * 情景记忆：记录一次任务执行的完整过程。
 */
public class Episode {
    private Long id;
    private String tenantId;
    private String userId;
    private String projectId;
    private String taskSummary;
    private Outcome outcome;
    private String failureCode;
    private List<String> actions;
    private List<String> observations;
    private String resolution;
    private double importance;
    private int accessCount;
    private long lastAccessedAt;
    private long createdAt;

    public enum Outcome {
        SUCCESS, FAILURE, PARTIAL
    }

    public Episode() {
        this.actions = new ArrayList<>();
        this.observations = new ArrayList<>();
        this.importance = 0.5;
        this.accessCount = 0;
        this.createdAt = System.currentTimeMillis();
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

    public String getTaskSummary() { return taskSummary; }
    public void setTaskSummary(String taskSummary) { this.taskSummary = taskSummary; }

    public Outcome getOutcome() { return outcome; }
    public void setOutcome(Outcome outcome) { this.outcome = outcome; }

    public String getFailureCode() { return failureCode; }
    public void setFailureCode(String failureCode) { this.failureCode = failureCode; }

    public List<String> getActions() { return actions; }
    public void setActions(List<String> actions) { this.actions = actions; }
    public void addAction(String action) { this.actions.add(action); }

    public List<String> getObservations() { return observations; }
    public void setObservations(List<String> observations) { this.observations = observations; }
    public void addObservation(String observation) { this.observations.add(observation); }

    public String getResolution() { return resolution; }
    public void setResolution(String resolution) { this.resolution = resolution; }

    public double getImportance() { return importance; }
    public void setImportance(double importance) { this.importance = importance; }

    public int getAccessCount() { return accessCount; }
    public void setAccessCount(int accessCount) { this.accessCount = accessCount; }

    public long getLastAccessedAt() { return lastAccessedAt; }
    public void setLastAccessedAt(long lastAccessedAt) { this.lastAccessedAt = lastAccessedAt; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public void touch() {
        this.accessCount++;
        this.lastAccessedAt = System.currentTimeMillis();
    }
}
