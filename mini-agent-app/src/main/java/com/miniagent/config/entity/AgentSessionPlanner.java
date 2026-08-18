package com.miniagent.config.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

@Entity
@Table(name = "agent_session_planner")
public class AgentSessionPlanner extends BaseEntity {

    @Id
    @Column(name = "session_id", length = 100, nullable = false)
    private String sessionId;

    /** 与 StateSnapshot.version 对齐，跨副本 CAS 用。 */
    @Column(name = "planner_version", nullable = false)
    private long plannerVersion;

    @Lob
    @Column(name = "state_json", columnDefinition = "LONGTEXT", nullable = false)
    private String stateJson = "{}";

    @Lob
    @Column(name = "events_json", columnDefinition = "LONGTEXT")
    private String eventsJson = "[]";

    @PrePersist
    @PreUpdate
    void touch() {
        if (stateJson == null || stateJson.isBlank()) stateJson = "{}";
        if (eventsJson == null || eventsJson.isBlank()) eventsJson = "[]";
    }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public long getPlannerVersion() { return plannerVersion; }
    public void setPlannerVersion(long plannerVersion) { this.plannerVersion = plannerVersion; }
    public String getStateJson() { return stateJson; }
    public void setStateJson(String stateJson) { this.stateJson = stateJson; }
    public String getEventsJson() { return eventsJson; }
    public void setEventsJson(String eventsJson) { this.eventsJson = eventsJson; }
}
