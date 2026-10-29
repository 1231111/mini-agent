package com.miniagent.agent.memory.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "agent_project_authorization_outbox",
        uniqueConstraints = @UniqueConstraint(name = "ux_apao_version",
                columnNames = {"tenant_id", "project_id", "authorization_version"}),
        indexes = @Index(name = "idx_apao_pending", columnList = "status,next_attempt_at"))
public class AgentProjectAuthorizationOutboxEntity extends BaseMemoryEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;
    @Column(name = "project_id", nullable = false, length = 128)
    private String projectId;
    @Column(name = "owner_user_id", nullable = false)
    private Long ownerUserId;
    @Lob
    @Column(name = "member_user_ids_json", nullable = false, columnDefinition = "LONGTEXT")
    private String memberUserIdsJson;
    @Column(name = "authorization_version", nullable = false)
    private Long authorizationVersion;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status = Status.PENDING;
    @Column(name = "attempts", nullable = false)
    private int attempts;
    @Column(name = "next_attempt_at", nullable = false)
    private LocalDateTime nextAttemptAt = LocalDateTime.now();
    @Column(name = "last_error", length = 1000)
    private String lastError;

    public enum Status { PENDING, PROCESSING, DONE, FAILED }

    public Long getId() { return id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String value) { tenantId = value; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String value) { projectId = value; }
    public Long getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(Long value) { ownerUserId = value; }
    public String getMemberUserIdsJson() { return memberUserIdsJson; }
    public void setMemberUserIdsJson(String value) { memberUserIdsJson = value; }
    public Long getAuthorizationVersion() { return authorizationVersion; }
    public void setAuthorizationVersion(Long value) { authorizationVersion = value; }
    public Status getStatus() { return status; }
    public void setStatus(Status value) { status = value; }
    public int getAttempts() { return attempts; }
    public void setAttempts(int value) { attempts = value; }
    public LocalDateTime getNextAttemptAt() { return nextAttemptAt; }
    public void setNextAttemptAt(LocalDateTime value) { nextAttemptAt = value; }
    public String getLastError() { return lastError; }
    public void setLastError(String value) { lastError = value; }
}
