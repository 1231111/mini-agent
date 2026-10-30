package com.miniagent.agent.memory.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "agent_failure_evidence_outbox", indexes = {
        @Index(name = "idx_afeo_due", columnList = "status,next_attempt_at")
}, uniqueConstraints = @UniqueConstraint(name = "ux_afeo_source",
        columnNames = {"tenant_id", "user_id", "session_id", "task_id", "node_id", "procedure_id", "procedure_version"}))
public class AgentFailureEvidenceOutboxEntity extends BaseMemoryEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "tenant_id", nullable = false, length = 64) private String tenantId;
    @Column(name = "user_id", nullable = false) private Long userId;
    @Column(name = "session_id", nullable = false, length = 100) private String sessionId;
    @Column(name = "task_id", nullable = false) private Long taskId;
    @Column(name = "node_id", nullable = false, length = 128) private String nodeId;
    @Column(name = "procedure_id", nullable = false) private Long procedureId;
    @Column(name = "procedure_version", nullable = false) private Integer procedureVersion;
    @Column(name = "failure_code", nullable = false, length = 128) private String failureCode;
    @Column(name = "task_summary", nullable = false, length = 500) private String taskSummary;
    @Lob @Column(name = "resolution", nullable = false, columnDefinition = "TEXT") private String resolution;
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
    public String getFailureCode() { return failureCode; } public void setFailureCode(String v) { failureCode = v; }
    public String getTaskSummary() { return taskSummary; } public void setTaskSummary(String v) { taskSummary = v; }
    public String getResolution() { return resolution; } public void setResolution(String v) { resolution = v; }
    public Status getStatus() { return status; } public void setStatus(Status v) { status = v; }
    public int getAttempts() { return attempts; } public void setAttempts(int v) { attempts = v; }
    public LocalDateTime getNextAttemptAt() { return nextAttemptAt; } public void setNextAttemptAt(LocalDateTime v) { nextAttemptAt = v; }
    public LocalDateTime getLeaseUntil() { return leaseUntil; } public void setLeaseUntil(LocalDateTime v) { leaseUntil = v; }
    public String getClaimToken() { return claimToken; } public void setClaimToken(String v) { claimToken = v; }
    public String getLastError() { return lastError; } public void setLastError(String v) { lastError = v; }
}
