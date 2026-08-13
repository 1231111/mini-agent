package com.miniagent.memory.model;

import java.util.*;

/**
 * 程序性记忆：可复用的 SOP / Playbook。
 */
public class Procedure {
    private Long id;
    private String tenantId;
    private MemoryScope scope;
    private String name;
    private String description;
    private List<String> preconditions;
    private List<String> steps;
    private List<String> successConditions;
    private int usageCount;
    private double successRate;
    private double importance;
    private MemoryStatus status;
    private long createdAt;
    private long updatedAt;

    public Procedure() {
        this.preconditions = new ArrayList<>();
        this.steps = new ArrayList<>();
        this.successConditions = new ArrayList<>();
        this.usageCount = 0;
        this.successRate = 0.0;
        this.importance = 0.5;
        this.status = MemoryStatus.ACTIVE;
        this.createdAt = System.currentTimeMillis();
        this.updatedAt = this.createdAt;
    }

    // --- getters/setters ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public MemoryScope getScope() { return scope; }
    public void setScope(MemoryScope scope) { this.scope = scope; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public List<String> getPreconditions() { return preconditions; }
    public void setPreconditions(List<String> preconditions) { this.preconditions = preconditions; }

    public List<String> getSteps() { return steps; }
    public void setSteps(List<String> steps) { this.steps = steps; }

    public List<String> getSuccessConditions() { return successConditions; }
    public void setSuccessConditions(List<String> successConditions) { this.successConditions = successConditions; }

    public int getUsageCount() { return usageCount; }
    public void setUsageCount(int usageCount) { this.usageCount = usageCount; }

    public double getSuccessRate() { return successRate; }
    public void setSuccessRate(double successRate) { this.successRate = successRate; }

    public double getImportance() { return importance; }
    public void setImportance(double importance) { this.importance = importance; }

    public MemoryStatus getStatus() { return status; }
    public void setStatus(MemoryStatus status) { this.status = status; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }
}
