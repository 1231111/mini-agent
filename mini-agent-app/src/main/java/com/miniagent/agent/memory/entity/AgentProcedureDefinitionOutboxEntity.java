package com.miniagent.agent.memory.entity;

import jakarta.persistence.*;

/**
 * 待投递到独立记忆服务的 SOP 版本快照。
 *
 * <p>存的是「这一版当时的样子」，不是 procedureId 指针。原因见
 * {@code V17__ProcedureDefinitionOutbox}：本地升版会覆盖同一行，指针式队列在积压后
 * 只能投最新版，中间版本直接消失，服务端看到的版本序列就出现空洞。</p>
 */
@Entity
@Table(name = "agent_procedure_definition_outbox",
        indexes = {
                @Index(name = "idx_apdo_pending", columnList = "status,next_attempt_at")
        },
        uniqueConstraints = {
                @UniqueConstraint(name = "ux_apdo_version",
                        columnNames = {"tenant_id", "procedure_id", "procedure_version"})
        })
public class AgentProcedureDefinitionOutboxEntity extends BaseMemoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;
    @Column(name = "user_id", nullable = false)
    private Long userId;
    @Column(name = "project_id", length = 128)
    private String projectId;
    @Column(name = "procedure_id", nullable = false)
    private Long procedureId;
    @Column(name = "procedure_version", nullable = false)
    private Integer procedureVersion;
    @Column(name = "scope_type", nullable = false, length = 32)
    private String scopeType;
    @Column(name = "scope_id", nullable = false, length = 128)
    private String scopeId;
    @Column(name = "name", nullable = false, length = 256)
    private String name;

    @Lob
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;
    @Lob
    @Column(name = "preconditions_json", columnDefinition = "TEXT")
    private String preconditionsJson;
    @Lob
    @Column(name = "steps_json", nullable = false, columnDefinition = "LONGTEXT")
    private String stepsJson;
    @Lob
    @Column(name = "success_conditions_json", columnDefinition = "TEXT")
    private String successConditionsJson;

    @Column(name = "importance")
    private Double importance;

    /** SOP 自身的状态（ACTIVE / ARCHIVED / DELETED）。不能与下面的投递状态共用列名。 */
    @Column(name = "procedure_status", nullable = false, length = 32)
    private String procedureStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status = Status.PENDING;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private java.time.LocalDateTime nextAttemptAt = java.time.LocalDateTime.now();

    @Column(name = "last_error", length = 1000)
    private String lastError;

    public enum Status { PENDING, PROCESSING, DONE, FAILED }

    public Long getId() { return id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public Long getProcedureId() { return procedureId; }
    public void setProcedureId(Long procedureId) { this.procedureId = procedureId; }
    public Integer getProcedureVersion() { return procedureVersion; }
    public void setProcedureVersion(Integer procedureVersion) { this.procedureVersion = procedureVersion; }
    public String getScopeType() { return scopeType; }
    public void setScopeType(String scopeType) { this.scopeType = scopeType; }
    public String getScopeId() { return scopeId; }
    public void setScopeId(String scopeId) { this.scopeId = scopeId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getPreconditionsJson() { return preconditionsJson; }
    public void setPreconditionsJson(String preconditionsJson) { this.preconditionsJson = preconditionsJson; }
    public String getStepsJson() { return stepsJson; }
    public void setStepsJson(String stepsJson) { this.stepsJson = stepsJson; }
    public String getSuccessConditionsJson() { return successConditionsJson; }
    public void setSuccessConditionsJson(String successConditionsJson) { this.successConditionsJson = successConditionsJson; }
    public Double getImportance() { return importance; }
    public void setImportance(Double importance) { this.importance = importance; }
    public String getProcedureStatus() { return procedureStatus; }
    public void setProcedureStatus(String procedureStatus) { this.procedureStatus = procedureStatus; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }
    public java.time.LocalDateTime getNextAttemptAt() { return nextAttemptAt; }
    public void setNextAttemptAt(java.time.LocalDateTime nextAttemptAt) { this.nextAttemptAt = nextAttemptAt; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
}
