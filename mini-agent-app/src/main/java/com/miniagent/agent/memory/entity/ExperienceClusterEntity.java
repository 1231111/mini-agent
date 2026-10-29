package com.miniagent.agent.memory.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "experience_clusters", uniqueConstraints = @UniqueConstraint(name = "ux_ec_key",
        columnNames = {"tenant_id", "scope_type", "scope_id", "cluster_key"}))
public class ExperienceClusterEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "tenant_id", nullable = false, length = 64) private String tenantId;
    @Column(name = "owner_user_id", nullable = false) private Long ownerUserId;
    @Column(name = "scope_type", nullable = false, length = 16) private String scopeType;
    @Column(name = "scope_id", nullable = false, length = 128) private String scopeId;
    @Column(name = "cluster_key", nullable = false, length = 128) private String clusterKey;
    @Column(name = "label", nullable = false, length = 256) private String label;
    @Column(name = "version", nullable = false) private Integer version = 1;
    @Column(name = "status", nullable = false, length = 16) private String status = "ACTIVE";
    @Column(name = "created_at", nullable = false) private LocalDateTime createdAt;
    @Column(name = "updated_at", nullable = false) private LocalDateTime updatedAt;
    @PrePersist void onCreate() { if (createdAt == null) createdAt = LocalDateTime.now(); if (updatedAt == null) updatedAt = createdAt; }
    public Long getId() { return id; }
    public String getTenantId() { return tenantId; } public void setTenantId(String v) { tenantId = v; }
    public Long getOwnerUserId() { return ownerUserId; } public void setOwnerUserId(Long v) { ownerUserId = v; }
    public String getScopeType() { return scopeType; } public void setScopeType(String v) { scopeType = v; }
    public String getScopeId() { return scopeId; } public void setScopeId(String v) { scopeId = v; }
    public String getClusterKey() { return clusterKey; } public void setClusterKey(String v) { clusterKey = v; }
    public String getLabel() { return label; } public void setLabel(String v) { label = v; }
    public Integer getVersion() { return version; } public void setVersion(Integer v) { version = v; }
    public String getStatus() { return status; } public void setStatus(String v) { status = v; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; } public void setUpdatedAt(LocalDateTime v) { updatedAt = v; }
}
