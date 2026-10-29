package com.miniagent.agent.memory.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "agent_procedure_revisions", uniqueConstraints = @UniqueConstraint(
        name = "ux_apr_version", columnNames = {"tenant_id", "procedure_id", "procedure_version"}))
public class AgentProcedureRevisionEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;
    @Column(name = "procedure_id", nullable = false)
    private Long procedureId;
    @Column(name = "procedure_version", nullable = false)
    private Integer procedureVersion;
    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;
    @Lob @Column(name = "content_json", nullable = false, columnDefinition = "LONGTEXT")
    private String contentJson;
    @Column(name = "source_type", nullable = false, length = 24)
    private String sourceType;
    @Column(name = "source_id", length = 128)
    private String sourceId;
    @Column(name = "actor_user_id")
    private Long actorUserId;
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
    public Long getId() { return id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String v) { tenantId = v; }
    public Long getProcedureId() { return procedureId; }
    public void setProcedureId(Long v) { procedureId = v; }
    public Integer getProcedureVersion() { return procedureVersion; }
    public void setProcedureVersion(Integer v) { procedureVersion = v; }
    public String getContentHash() { return contentHash; }
    public void setContentHash(String v) { contentHash = v; }
    public String getContentJson() { return contentJson; }
    public void setContentJson(String v) { contentJson = v; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String v) { sourceType = v; }
    public String getSourceId() { return sourceId; }
    public void setSourceId(String v) { sourceId = v; }
    public Long getActorUserId() { return actorUserId; }
    public void setActorUserId(Long v) { actorUserId = v; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime v) { createdAt = v; }
    @PrePersist void onCreate() { if (createdAt == null) createdAt = LocalDateTime.now(); }
}
