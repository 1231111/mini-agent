package com.miniagent.agent.memory.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 一条 SOP 在某个归属域里只有一行。
 *
 * <p><b>为什么必须有 {@code ux_ap_identity}</b>：identity 是
 * {@code (tenant_id, scope_type, scope_id, name)}，{@code procedure_version} 只是这一行的属性，
 * 不是 identity 的一部分。历史实现每次写入都无条件 insert 且版本恒为 1，同名 SOP 会沉淀出
 * 多行 (name, version=1)；规划器把它们当成不同的 Procedure 列出，节点结算的计数又只落到
 * 其中一行，闭环因此拿不到稳定反馈。唯一键把这个状态从「靠代码自觉」变成「库层面不允许」。</p>
 *
 * <p>桌面档的库结构由 {@code ddl-auto: update} 从实体反推，这里声明的唯一键会由 Hibernate 建；
 * prod 档是 {@code ddl-auto: validate}，唯一键由 V13 迁移建。两处必须同名同列，
 * 否则会在 prod 上出现「迁移建了一个、校验不认识另一个」的漂移。</p>
 */
@Entity
@Table(name = "agent_procedures",
    indexes = {
        @Index(name = "idx_ap_scope", columnList = "scope_type, scope_id"),
        @Index(name = "idx_ap_name", columnList = "name")
    },
    uniqueConstraints = {
        @UniqueConstraint(name = "ux_ap_identity",
            columnNames = {"tenant_id", "scope_type", "scope_id", "name"})
    })
public class AgentProcedureEntity extends BaseMemoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type", nullable = false, length = 32)
    private AgentMemoryEntryEntity.ScopeType scopeType;

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

    @Column(name = "usage_count")
    private Integer usageCount = 0;

    @Column(name = "success_rate")
    private Double successRate = 0.0;

    @Column(name = "procedure_version", nullable = false)
    private Integer procedureVersion = 1;

    @Column(name = "success_count", nullable = false)
    private Long successCount = 0L;

    @Column(name = "attempt_count", nullable = false)
    private Long attemptCount = 0L;

    @Column(name = "importance")
    private Double importance = 0.5;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 32)
    private AgentMemoryEntryEntity.Status status = AgentMemoryEntryEntity.Status.ACTIVE;

    // createdAt/updatedAt 由 BaseMemoryEntity 管理

    // --- getters/setters ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public AgentMemoryEntryEntity.ScopeType getScopeType() { return scopeType; }
    public void setScopeType(AgentMemoryEntryEntity.ScopeType scopeType) { this.scopeType = scopeType; }

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

    public Integer getUsageCount() { return usageCount; }
    public void setUsageCount(Integer usageCount) { this.usageCount = usageCount; }

    public Double getSuccessRate() { return successRate; }
    public void setSuccessRate(Double successRate) { this.successRate = successRate; }

    public Integer getProcedureVersion() { return procedureVersion; }
    public void setProcedureVersion(Integer procedureVersion) { this.procedureVersion = procedureVersion; }

    public Long getSuccessCount() { return successCount; }
    public void setSuccessCount(Long successCount) { this.successCount = successCount; }

    public Long getAttemptCount() { return attemptCount; }
    public void setAttemptCount(Long attemptCount) { this.attemptCount = attemptCount; }

    public Double getImportance() { return importance; }
    public void setImportance(Double importance) { this.importance = importance; }

    public AgentMemoryEntryEntity.Status getStatus() { return status; }
    public void setStatus(AgentMemoryEntryEntity.Status status) { this.status = status; }
}
