package com.miniagent.agent.memory.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "agent_procedure_applications", uniqueConstraints = @UniqueConstraint(
        name = "ux_apa_attempt", columnNames = {"tenant_id", "user_id", "session_id", "task_id",
                "node_id", "procedure_id", "procedure_version"}))
public class AgentProcedureApplicationEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;
    @Column(name = "user_id", nullable = false)
    private Long userId;
    @Column(name = "session_id", nullable = false, length = 100)
    private String sessionId;
    @Column(name = "task_id", nullable = false)
    private Long taskId;
    @Column(name = "node_id", nullable = false, length = 128)
    private String nodeId;
    @Column(name = "procedure_id", nullable = false)
    private Long procedureId;
    @Column(name = "procedure_version", nullable = false)
    private Integer procedureVersion;
    @Column(name = "action_id", nullable = false, length = 128)
    private String actionId;
    @Enumerated(EnumType.STRING)
    @Column(name = "result", nullable = false, length = 16)
    private Result result;
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() { if (createdAt == null) createdAt = LocalDateTime.now(); }

    public enum Result { UNKNOWN, SUCCESS, FAILURE }
    public Long getId() { return id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }
    public Long getProcedureId() { return procedureId; }
    public void setProcedureId(Long procedureId) { this.procedureId = procedureId; }
    public Integer getProcedureVersion() { return procedureVersion; }
    public void setProcedureVersion(Integer procedureVersion) { this.procedureVersion = procedureVersion; }
    public String getActionId() { return actionId; }
    public void setActionId(String actionId) { this.actionId = actionId; }
    public Result getResult() { return result; }
    public void setResult(Result result) { this.result = result; }
}
