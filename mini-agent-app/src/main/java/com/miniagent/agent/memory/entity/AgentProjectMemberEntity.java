package com.miniagent.agent.memory.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.util.Objects;

@Entity
@Table(name = "agent_project_members")
@IdClass(AgentProjectMemberEntity.Key.class)
public class AgentProjectMemberEntity {
    @Id
    @Column(name = "project_id", length = 128)
    private String projectId;

    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "role", nullable = false, length = 16)
    private String role;

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public static class Key implements Serializable {
        private String projectId;
        private Long userId;
        public Key() {}
        public Key(String projectId, Long userId) { this.projectId = projectId; this.userId = userId; }
        @Override public boolean equals(Object o) {
            return o instanceof Key key && Objects.equals(projectId, key.projectId)
                    && Objects.equals(userId, key.userId);
        }
        @Override public int hashCode() { return Objects.hash(projectId, userId); }
    }
}
