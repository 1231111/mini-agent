package com.miniagent.memory.model;

import java.util.*;

/**
 * 程序性记忆：可复用的 SOP / Playbook。
 */
public class Procedure {
    private Long id;
    private Integer version;
    private String tenantId;
    private MemoryScope scope;
    private String name;
    private String description;
    private List<String> preconditions;
    private List<String> steps;
    private List<String> successConditions;
    private int usageCount;
    private double successRate;
    /**
     * 当前版本被结算过的节点数与其中成功的节点数。
     *
     * <p>只有 {@code successRate} 时无法区分「1 次成功」和「100 次成功」，
     * 而这个闭环的全部意义就是让「这一版 SOP 到底靠不靠得住」可判断，所以两个原始计数一起给出。</p>
     */
    private long attemptCount;
    private long successCount;
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

    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }

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

    public long getAttemptCount() { return attemptCount; }
    public void setAttemptCount(long attemptCount) { this.attemptCount = attemptCount; }

    public long getSuccessCount() { return successCount; }
    public void setSuccessCount(long successCount) { this.successCount = successCount; }

    public double getImportance() { return importance; }
    public void setImportance(double importance) { this.importance = importance; }

    public MemoryStatus getStatus() { return status; }
    public void setStatus(MemoryStatus status) { this.status = status; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }
}
