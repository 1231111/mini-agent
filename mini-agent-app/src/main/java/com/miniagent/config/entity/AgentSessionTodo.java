package com.miniagent.config.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.LocalDateTime;
import java.util.Objects;

@Entity
@Table(name = "agent_session_todos")
public class AgentSessionTodo {

    @Id
    @Column(name = "session_id", length = 100, nullable = false)
    private String sessionId;

    @Lob
    @Column(name = "active_json", columnDefinition = "LONGTEXT")
    private String activeJson = "[]";

    @Lob
    @Column(name = "suspended_json", columnDefinition = "LONGTEXT")
    private String suspendedJson;

    @Version
    @Column(name = "version")
    private Long version;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = LocalDateTime.now();
        if (Objects.isNull(activeJson) || activeJson.isBlank()) {
            activeJson = "[]";
        }
    }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getActiveJson() { return activeJson; }
    public void setActiveJson(String activeJson) { this.activeJson = activeJson; }
    public String getSuspendedJson() { return suspendedJson; }
    public void setSuspendedJson(String suspendedJson) { this.suspendedJson = suspendedJson; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
