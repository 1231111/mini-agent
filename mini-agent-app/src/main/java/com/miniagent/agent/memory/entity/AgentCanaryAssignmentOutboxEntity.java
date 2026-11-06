package com.miniagent.agent.memory.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "agent_canary_assignment_outbox", indexes = {
        @Index(name = "idx_acao_due", columnList = "status,next_attempt_at")
}, uniqueConstraints = @UniqueConstraint(name = "ux_acao_assignment",
        columnNames = {"tenant_id", "user_id", "session_id", "task_id", "node_id", "procedure_id", "proposal_id", "event_type"}))
public class AgentCanaryAssignmentOutboxEntity extends BaseMemoryEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "tenant_id", nullable = false, length = 64) private String tenantId;
    @Column(name = "user_id", nullable = false) private Long userId;
    @Column(name = "session_id", nullable = false, length = 100) private String sessionId;
    @Column(name = "task_id", nullable = false) private Long taskId;
    @Column(name = "node_id", nullable = false, length = 128) private String nodeId;
    @Column(name = "procedure_id", nullable = false) private Long procedureId;
    @Column(name = "procedure_version", nullable = false) private Integer procedureVersion;
    @Column(name = "proposal_id", nullable = false) private Long proposalId;
    @Column(name = "candidate_hash", nullable = false, length = 64) private String candidateHash;
    @Column(name = "variant", nullable = false, length = 16) private String variant;
    @Column(name = "target_percent", nullable = false) private Integer targetPercent;
    @Column(name = "bucket", nullable = false) private Integer bucket;
    @Column(name = "router_attestation", nullable = false, length = 512) private String routerAttestation;
    @Column(name = "event_type", nullable = false, length = 16) private String eventType;
    @Column(name = "passed") private Boolean passed;
    @Column(name = "observation_id", length = 160) private String observationId;
    @Enumerated(EnumType.STRING) @Column(name = "status", nullable = false, length = 16) private Status status = Status.PENDING;
    @Column(name = "attempts", nullable = false) private int attempts;
    @Column(name = "next_attempt_at", nullable = false) private LocalDateTime nextAttemptAt = LocalDateTime.now();
    @Column(name = "lease_until") private LocalDateTime leaseUntil;
    @Column(name = "claim_token", length = 36) private String claimToken;
    @Column(name = "last_error", length = 1000) private String lastError;
    public enum Status { PENDING, PROCESSING, DONE, FAILED }
    public Long getId() { return id; }
    public String getTenantId() { return tenantId; } public void setTenantId(String v) { tenantId = v; }
    public Long getUserId() { return userId; } public void setUserId(Long v) { userId = v; }
    public String getSessionId() { return sessionId; } public void setSessionId(String v) { sessionId = v; }
    public Long getTaskId() { return taskId; } public void setTaskId(Long v) { taskId = v; }
    public String getNodeId() { return nodeId; } public void setNodeId(String v) { nodeId = v; }
    public Long getProcedureId() { return procedureId; } public void setProcedureId(Long v) { procedureId = v; }
    public Integer getProcedureVersion() { return procedureVersion; } public void setProcedureVersion(Integer v) { procedureVersion = v; }
    public Long getProposalId() { return proposalId; } public void setProposalId(Long v) { proposalId = v; }
    public String getCandidateHash() { return candidateHash; } public void setCandidateHash(String v) { candidateHash = v; }
    public String getVariant() { return variant; } public void setVariant(String v) { variant = v; }
    public Integer getTargetPercent() { return targetPercent; } public void setTargetPercent(Integer v) { targetPercent = v; }
    public Integer getBucket() { return bucket; } public void setBucket(Integer v) { bucket = v; }
    public String getRouterAttestation() { return routerAttestation; } public void setRouterAttestation(String v) { routerAttestation = v; }
    public String getEventType() { return eventType; } public void setEventType(String v) { eventType = v; }
    public Boolean getPassed() { return passed; } public void setPassed(Boolean v) { passed = v; }
    public String getObservationId() { return observationId; } public void setObservationId(String v) { observationId = v; }
    public Status getStatus() { return status; } public void setStatus(Status v) { status = v; }
    public int getAttempts() { return attempts; } public void setAttempts(int v) { attempts = v; }
    public LocalDateTime getNextAttemptAt() { return nextAttemptAt; } public void setNextAttemptAt(LocalDateTime v) { nextAttemptAt = v; }
    public LocalDateTime getLeaseUntil() { return leaseUntil; } public void setLeaseUntil(LocalDateTime v) { leaseUntil = v; }
    public String getClaimToken() { return claimToken; } public void setClaimToken(String v) { claimToken = v; }
    public String getLastError() { return lastError; } public void setLastError(String v) { lastError = v; }
}
