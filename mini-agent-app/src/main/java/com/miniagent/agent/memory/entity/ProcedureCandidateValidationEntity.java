package com.miniagent.agent.memory.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "agent_procedure_candidate_validations", uniqueConstraints = @UniqueConstraint(
        name = "ux_apcv_proposal_verifier", columnNames = {"proposal_id", "verifier_user_id"}))
public class ProcedureCandidateValidationEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;
    @Column(name = "proposal_id", nullable = false)
    private Long proposalId;
    @Column(name = "candidate_hash", nullable = false, length = 64)
    private String candidateHash;
    @Column(name = "verifier_user_id", nullable = false)
    private Long verifierUserId;
    @Column(name = "passed", nullable = false)
    private boolean passed;
    @Lob
    @Column(name = "evidence_json", nullable = false, columnDefinition = "LONGTEXT")
    private String evidenceJson;
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() { if (createdAt == null) createdAt = LocalDateTime.now(); }

    public Long getId() { return id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String value) { tenantId = value; }
    public Long getProposalId() { return proposalId; }
    public void setProposalId(Long value) { proposalId = value; }
    public String getCandidateHash() { return candidateHash; }
    public void setCandidateHash(String value) { candidateHash = value; }
    public Long getVerifierUserId() { return verifierUserId; }
    public void setVerifierUserId(Long value) { verifierUserId = value; }
    public boolean isPassed() { return passed; }
    public void setPassed(boolean value) { passed = value; }
    public String getEvidenceJson() { return evidenceJson; }
    public void setEvidenceJson(String value) { evidenceJson = value; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
