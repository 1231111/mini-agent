package com.miniagent.agent.memory.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "experience_cluster_members", uniqueConstraints = @UniqueConstraint(
        name = "ux_ecm_episode", columnNames = {"tenant_id", "episode_id"}))
public class ExperienceClusterMemberEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "tenant_id", nullable = false, length = 64) private String tenantId;
    @Column(name = "cluster_id", nullable = false) private Long clusterId;
    @Column(name = "episode_id", nullable = false) private Long episodeId;
    @Column(name = "created_at", nullable = false) private LocalDateTime createdAt;
    @PrePersist void onCreate() { if (createdAt == null) createdAt = LocalDateTime.now(); }
    public Long getId() { return id; }
    public String getTenantId() { return tenantId; } public void setTenantId(String v) { tenantId = v; }
    public Long getClusterId() { return clusterId; } public void setClusterId(Long v) { clusterId = v; }
    public Long getEpisodeId() { return episodeId; } public void setEpisodeId(Long v) { episodeId = v; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
