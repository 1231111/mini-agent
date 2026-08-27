package com.miniagent.config.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "agent_session_permissions")
public class AgentSessionPermission extends BaseEntity {
    @Id
    @Column(name = "session_id", length = 100, nullable = false)
    private String sessionId;

    @Column(nullable = false, length = 32)
    private String mode;

    @Column(name = "plan_approved", nullable = false)
    private boolean planApproved;

    @Column(name = "ask_grants_json", columnDefinition = "TEXT")
    private String askGrantsJson;

    @Column(name = "confirm_policy", nullable = false, length = 32)
    private String confirmPolicy;

    @Version
    @Column(nullable = false)
    private long version;

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public boolean isPlanApproved() { return planApproved; }
    public void setPlanApproved(boolean planApproved) { this.planApproved = planApproved; }
    public String getAskGrantsJson() { return askGrantsJson; }
    public void setAskGrantsJson(String askGrantsJson) { this.askGrantsJson = askGrantsJson; }
    public String getConfirmPolicy() { return confirmPolicy; }
    public void setConfirmPolicy(String confirmPolicy) { this.confirmPolicy = confirmPolicy; }
    public long getVersion() { return version; }
}
