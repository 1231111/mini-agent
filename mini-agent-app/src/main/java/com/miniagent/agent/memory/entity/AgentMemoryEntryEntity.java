package com.miniagent.agent.memory.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "agent_memory_entries", indexes = {
    @Index(name = "idx_ame_tenant_type", columnList = "tenant_id, memory_type"),
    @Index(name = "idx_ame_scope", columnList = "scope_type, scope_id"),
    @Index(name = "idx_ame_status", columnList = "status"),
    @Index(name = "idx_ame_importance", columnList = "importance")
})
public class AgentMemoryEntryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "memory_type", nullable = false, length = 32)
    private MemoryType memoryType;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type", nullable = false, length = 32)
    private ScopeType scopeType;

    @Column(name = "scope_id", nullable = false, length = 128)
    private String scopeId;

    @Lob
    @Column(name = "content", nullable = false, columnDefinition = "LONGTEXT")
    private String content;

    @Column(name = "summary", length = 500)
    private String summary;

    @Column(name = "importance")
    private Double importance = 0.5;

    @Column(name = "confidence")
    private Double confidence = 0.5;

    @Column(name = "access_count")
    private Integer accessCount = 0;

    @Column(name = "last_accessed_at")
    private LocalDateTime lastAccessedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 32)
    private Status status = Status.ACTIVE;

    @Column(name = "version_num")
    private Integer versionNum = 1;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", length = 32)
    private SourceType sourceType;

    @Column(name = "source_id", length = 128)
    private String sourceId;

    @Column(name = "parent_id")
    private Long parentId;

    @Lob
    @Column(name = "metadata_json", columnDefinition = "TEXT")
    private String metadataJson;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public enum MemoryType {
        WORKING, EPISODIC, SEMANTIC, PROCEDURAL, USER, PROJECT, ORGANIZATION
    }

    public enum ScopeType {
        TENANT, ORGANIZATION, USER, PROJECT, AGENT, SESSION
    }

    public enum Status {
        ACTIVE, ARCHIVED, DELETED
    }

    public enum SourceType {
        USER_STATED, SYSTEM_CONFIG, TOOL_OBSERVED, AGENT_INFERRED, LLM_EXTRACTED
    }

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // --- getters/setters ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public MemoryType getMemoryType() { return memoryType; }
    public void setMemoryType(MemoryType memoryType) { this.memoryType = memoryType; }

    public ScopeType getScopeType() { return scopeType; }
    public void setScopeType(ScopeType scopeType) { this.scopeType = scopeType; }

    public String getScopeId() { return scopeId; }
    public void setScopeId(String scopeId) { this.scopeId = scopeId; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    public Double getImportance() { return importance; }
    public void setImportance(Double importance) { this.importance = importance; }

    public Double getConfidence() { return confidence; }
    public void setConfidence(Double confidence) { this.confidence = confidence; }

    public Integer getAccessCount() { return accessCount; }
    public void setAccessCount(Integer accessCount) { this.accessCount = accessCount; }

    public LocalDateTime getLastAccessedAt() { return lastAccessedAt; }
    public void setLastAccessedAt(LocalDateTime lastAccessedAt) { this.lastAccessedAt = lastAccessedAt; }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public Integer getVersionNum() { return versionNum; }
    public void setVersionNum(Integer versionNum) { this.versionNum = versionNum; }

    public SourceType getSourceType() { return sourceType; }
    public void setSourceType(SourceType sourceType) { this.sourceType = sourceType; }

    public String getSourceId() { return sourceId; }
    public void setSourceId(String sourceId) { this.sourceId = sourceId; }

    public Long getParentId() { return parentId; }
    public void setParentId(Long parentId) { this.parentId = parentId; }

    public String getMetadataJson() { return metadataJson; }
    public void setMetadataJson(String metadataJson) { this.metadataJson = metadataJson; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
