package com.miniagent.agent.memory.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "agent_procedure_rewrite_proposals", uniqueConstraints = @UniqueConstraint(
        name = "ux_aprp_source", columnNames = {"tenant_id", "procedure_id", "base_version", "episode_id"}),
        indexes = @Index(name = "idx_aprp_owner_status", columnList = "tenant_id,user_id,status,created_at"))
public class ProcedureRewriteProposalEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;
    @Column(name = "user_id", nullable = false)
    private Long userId;
    @Column(name = "procedure_id", nullable = false)
    private Long procedureId;
    @Column(name = "base_version", nullable = false)
    private Integer baseVersion;
    @Column(name = "episode_id", nullable = false)
    private Long episodeId;
    @Column(name = "procedure_name", nullable = false, length = 256)
    private String procedureName;
    @Lob
    @Column(name = "base_content_json", nullable = false, columnDefinition = "LONGTEXT")
    private String baseContentJson;
    @Lob
    @Column(name = "proposed_content_json", nullable = false, columnDefinition = "LONGTEXT")
    private String proposedContentJson;
    @Lob
    @Column(name = "evidence_json", nullable = false, columnDefinition = "LONGTEXT")
    private String evidenceJson;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status = Status.PENDING;
    @Column(name = "reviewed_by", length = 128)
    private String reviewedBy;
    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() { if (createdAt == null) createdAt = LocalDateTime.now(); }

    public enum Status { PENDING, APPROVED, REJECTED, CONFLICT }
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getProcedureId() { return procedureId; }
    public void setProcedureId(Long procedureId) { this.procedureId = procedureId; }
    public Integer getBaseVersion() { return baseVersion; }
    public void setBaseVersion(Integer baseVersion) { this.baseVersion = baseVersion; }
    public Long getEpisodeId() { return episodeId; }
    public void setEpisodeId(Long episodeId) { this.episodeId = episodeId; }
    public String getProcedureName() { return procedureName; }
    public void setProcedureName(String procedureName) { this.procedureName = procedureName; }
    public String getBaseContentJson() { return baseContentJson; }
    public void setBaseContentJson(String baseContentJson) { this.baseContentJson = baseContentJson; }
    public String getProposedContentJson() { return proposedContentJson; }
    public void setProposedContentJson(String proposedContentJson) { this.proposedContentJson = proposedContentJson; }
    public String getEvidenceJson() { return evidenceJson; }
    public void setEvidenceJson(String evidenceJson) { this.evidenceJson = evidenceJson; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public String getReviewedBy() { return reviewedBy; }
    public void setReviewedBy(String reviewedBy) { this.reviewedBy = reviewedBy; }
    public LocalDateTime getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(LocalDateTime reviewedAt) { this.reviewedAt = reviewedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
