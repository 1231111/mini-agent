package com.miniagent.agent.memory.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "agent_procedures", indexes = {
    @Index(name = "idx_ap_scope", columnList = "scope_type, scope_id"),
    @Index(name = "idx_ap_name", columnList = "name")
})
public class AgentProcedureEntity extends BaseMemoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type", nullable = false, length = 32)
    private AgentMemoryEntryEntity.ScopeType scopeType;

    @Column(name = "scope_id", nullable = false, length = 128)
    private String scopeId;

    @Column(name = "name", nullable = false, length = 256)
    private String name;

    @Lob
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Lob
    @Column(name = "preconditions_json", columnDefinition = "TEXT")
    private String preconditionsJson;

    @Lob
    @Column(name = "steps_json", nullable = false, columnDefinition = "LONGTEXT")
    private String stepsJson;

    @Lob
    @Column(name = "success_conditions_json", columnDefinition = "TEXT")
    private String successConditionsJson;

    @Column(name = "usage_count")
    private Integer usageCount = 0;

    @Column(name = "success_rate")
    private Double successRate = 0.0;

    @Column(name = "importance")
    private Double importance = 0.5;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 32)
    private AgentMemoryEntryEntity.Status status = AgentMemoryEntryEntity.Status.ACTIVE;

    // createdAt/updatedAt 由 BaseMemoryEntity 管理

    // --- getters/setters ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public AgentMemoryEntryEntity.ScopeType getScopeType() { return scopeType; }
    public void setScopeType(AgentMemoryEntryEntity.ScopeType scopeType) { this.scopeType = scopeType; }

    public String getScopeId() { return scopeId; }
    public void setScopeId(String scopeId) { this.scopeId = scopeId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getPreconditionsJson() { return preconditionsJson; }
    public void setPreconditionsJson(String preconditionsJson) { this.preconditionsJson = preconditionsJson; }

    public String getStepsJson() { return stepsJson; }
    public void setStepsJson(String stepsJson) { this.stepsJson = stepsJson; }

    public String getSuccessConditionsJson() { return successConditionsJson; }
    public void setSuccessConditionsJson(String successConditionsJson) { this.successConditionsJson = successConditionsJson; }

    public Integer getUsageCount() { return usageCount; }
    public void setUsageCount(Integer usageCount) { this.usageCount = usageCount; }

    public Double getSuccessRate() { return successRate; }
    public void setSuccessRate(Double successRate) { this.successRate = successRate; }

    public Double getImportance() { return importance; }
    public void setImportance(Double importance) { this.importance = importance; }

    public AgentMemoryEntryEntity.Status getStatus() { return status; }
    public void setStatus(AgentMemoryEntryEntity.Status status) { this.status = status; }
}
