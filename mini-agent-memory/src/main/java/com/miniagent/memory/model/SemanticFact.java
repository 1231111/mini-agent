package com.miniagent.memory.model;

/**
 * 语义事实：三元组 (Subject, Predicate, Object)。
 * 例如：(order-service, uses, PostgreSQL)
 */
public class SemanticFact {
    private Long id;
    private String tenantId;
    private MemoryScope scope;
    private String subject;
    private String predicate;
    private String objectValue;
    private double confidence;
    private Long validFrom;
    private Long validTo;          // null = 仍然有效
    private String source;
    private Long supersededBy;     // 被哪条记录取代
    private long createdAt;
    private long updatedAt;

    public SemanticFact() {
        this.confidence = 0.9;
        this.validFrom = System.currentTimeMillis();
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

    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }

    public String getPredicate() { return predicate; }
    public void setPredicate(String predicate) { this.predicate = predicate; }

    public String getObjectValue() { return objectValue; }
    public void setObjectValue(String objectValue) { this.objectValue = objectValue; }

    public double getConfidence() { return confidence; }
    public void setConfidence(double confidence) { this.confidence = confidence; }

    public Long getValidFrom() { return validFrom; }
    public void setValidFrom(Long validFrom) { this.validFrom = validFrom; }

    public Long getValidTo() { return validTo; }
    public void setValidTo(Long validTo) { this.validTo = validTo; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public Long getSupersededBy() { return supersededBy; }
    public void setSupersededBy(Long supersededBy) { this.supersededBy = supersededBy; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }

    /** 是否仍然有效 */
    public boolean isValid() {
        return validTo == null && supersededBy == null;
    }
}
