package com.miniagent.agent.memory.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "agent_semantic_facts", indexes = {
    @Index(name = "idx_asf_scope", columnList = "scope_type, scope_id"),
    @Index(name = "idx_asf_subject", columnList = "subject"),
    @Index(name = "idx_asf_triple", columnList = "subject, predicate")
})
public class AgentSemanticFactEntity extends BaseMemoryEntity {

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

    @Column(name = "subject", nullable = false, length = 256)
    private String subject;

    @Column(name = "predicate", nullable = false, length = 256)
    private String predicate;

    @Column(name = "object_value", nullable = false, length = 512)
    private String objectValue;

    @Column(name = "confidence")
    private Double confidence = 0.9;

    @Column(name = "valid_from")
    private LocalDateTime validFrom;

    @Column(name = "valid_to")
    private LocalDateTime validTo;

    @Column(name = "source", length = 64)
    private String source;

    @Column(name = "superseded_by")
    private Long supersededBy;

    // createdAt/updatedAt 由 BaseMemoryEntity 管理

    @PrePersist
    void onSemanticFactCreate() {
        if (validFrom == null) validFrom = getCreatedAt();
    }

    // --- getters/setters ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public AgentMemoryEntryEntity.ScopeType getScopeType() { return scopeType; }
    public void setScopeType(AgentMemoryEntryEntity.ScopeType scopeType) { this.scopeType = scopeType; }

    public String getScopeId() { return scopeId; }
    public void setScopeId(String scopeId) { this.scopeId = scopeId; }

    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }

    public String getPredicate() { return predicate; }
    public void setPredicate(String predicate) { this.predicate = predicate; }

    public String getObjectValue() { return objectValue; }
    public void setObjectValue(String objectValue) { this.objectValue = objectValue; }

    public Double getConfidence() { return confidence; }
    public void setConfidence(Double confidence) { this.confidence = confidence; }

    public LocalDateTime getValidFrom() { return validFrom; }
    public void setValidFrom(LocalDateTime validFrom) { this.validFrom = validFrom; }

    public LocalDateTime getValidTo() { return validTo; }
    public void setValidTo(LocalDateTime validTo) { this.validTo = validTo; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public Long getSupersededBy() { return supersededBy; }
    public void setSupersededBy(Long supersededBy) { this.supersededBy = supersededBy; }
}
