package com.miniagent.memory.model;

import java.util.Map;

/**
 * 核心记忆对象。
 */
public class MemoryEntry {
    private Long id;
    private String tenantId;
    private MemoryType memoryType;
    private MemoryScope scope;
    private String content;
    private String summary;
    private double importance;
    private double confidence;
    private int accessCount;
    private long lastAccessedAt;
    private MemoryStatus status;
    private int versionNum;
    private SourceType sourceType;
    private String sourceId;
    private Long parentId;        // 冲突链：旧版本指向新版本
    private Map<String, Object> metadata;
    private long createdAt;
    private long updatedAt;

    public MemoryEntry() {
        this.importance = 0.5;
        this.confidence = 0.5;
        this.accessCount = 0;
        this.status = MemoryStatus.ACTIVE;
        this.versionNum = 1;
        this.createdAt = System.currentTimeMillis();
        this.updatedAt = this.createdAt;
    }

    // --- getters/setters ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public MemoryType getMemoryType() { return memoryType; }
    public void setMemoryType(MemoryType memoryType) { this.memoryType = memoryType; }

    public MemoryScope getScope() { return scope; }
    public void setScope(MemoryScope scope) { this.scope = scope; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    public double getImportance() { return importance; }
    public void setImportance(double importance) { this.importance = importance; }

    public double getConfidence() { return confidence; }
    public void setConfidence(double confidence) { this.confidence = confidence; }

    public int getAccessCount() { return accessCount; }
    public void setAccessCount(int accessCount) { this.accessCount = accessCount; }

    public long getLastAccessedAt() { return lastAccessedAt; }
    public void setLastAccessedAt(long lastAccessedAt) { this.lastAccessedAt = lastAccessedAt; }

    public MemoryStatus getStatus() { return status; }
    public void setStatus(MemoryStatus status) { this.status = status; }

    public int getVersionNum() { return versionNum; }
    public void setVersionNum(int versionNum) { this.versionNum = versionNum; }

    public SourceType getSourceType() { return sourceType; }
    public void setSourceType(SourceType sourceType) { this.sourceType = sourceType; }

    public String getSourceId() { return sourceId; }
    public void setSourceId(String sourceId) { this.sourceId = sourceId; }

    public Long getParentId() { return parentId; }
    public void setParentId(Long parentId) { this.parentId = parentId; }

    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }

    /** 标记为已访问 */
    public void touch() {
        this.accessCount++;
        this.lastAccessedAt = System.currentTimeMillis();
    }
}
