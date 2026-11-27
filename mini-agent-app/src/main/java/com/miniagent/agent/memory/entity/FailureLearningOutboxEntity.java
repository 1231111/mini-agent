package com.miniagent.agent.memory.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** Durable trigger only. Episode text stays in the owner-scoped episode table. */
@Entity
@Table(name = "agent_failure_learning_outbox",
        uniqueConstraints = @UniqueConstraint(name = "ux_aflo_source", columnNames = {"episode_id", "source_hash"}),
        indexes = @Index(name = "idx_aflo_due", columnList = "status,next_attempt_at"))
public class FailureLearningOutboxEntity extends BaseMemoryEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "episode_id", nullable = false) private Long episodeId;
    @Column(name = "source_hash", nullable = false, length = 64) private String sourceHash;
    @Column(name = "tenant_id", nullable = false, length = 64) private String tenantId;
    @Column(name = "user_id", nullable = false) private Long userId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private Status status = Status.PENDING;
    @Column(nullable = false) private int attempts;
    @Column(name = "next_attempt_at", nullable = false) private LocalDateTime nextAttemptAt = LocalDateTime.now();
    @Column(name = "lease_until") private LocalDateTime leaseUntil;
    @Column(name = "claim_token", length = 36) private String claimToken;
    @Column(name = "last_error", length = 1000) private String lastError;
    public enum Status { PENDING, PROCESSING, DONE, FAILED }
    public Long getId() { return id; }
    public Long getEpisodeId() { return episodeId; }
    public void setEpisodeId(Long value) { episodeId = value; }
    public String getSourceHash() { return sourceHash; }
    public void setSourceHash(String value) { sourceHash = value; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String value) { tenantId = value; }
    public Long getUserId() { return userId; }
    public void setUserId(Long value) { userId = value; }
    public Status getStatus() { return status; }
    public void setStatus(Status value) { status = value; }
    public int getAttempts() { return attempts; }
    public void setAttempts(int value) { attempts = value; }
    public LocalDateTime getNextAttemptAt() { return nextAttemptAt; }
    public void setNextAttemptAt(LocalDateTime value) { nextAttemptAt = value; }
    public LocalDateTime getLeaseUntil() { return leaseUntil; }
    public void setLeaseUntil(LocalDateTime value) { leaseUntil = value; }
    public String getClaimToken() { return claimToken; }
    public void setClaimToken(String value) { claimToken = value; }
    public String getLastError() { return lastError; }
    public void setLastError(String value) { lastError = value; }
}
