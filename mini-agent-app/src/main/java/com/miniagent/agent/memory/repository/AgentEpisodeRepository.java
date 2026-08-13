package com.miniagent.agent.memory.repository;

import com.miniagent.agent.memory.entity.AgentEpisodeEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AgentEpisodeRepository extends JpaRepository<AgentEpisodeEntity, Long> {

    List<AgentEpisodeEntity> findByTenantIdAndProjectId(String tenantId, String projectId);

    List<AgentEpisodeEntity> findByTenantIdAndProjectIdAndOutcome(
        String tenantId, String projectId, AgentEpisodeEntity.Outcome outcome);

    List<AgentEpisodeEntity> findByTenantIdAndTaskSummaryContaining(
        String tenantId, String keyword);

    @Modifying
    @Query("UPDATE AgentEpisodeEntity e SET e.accessCount = e.accessCount + 1, " +
           "e.lastAccessedAt = CURRENT_TIMESTAMP WHERE e.id = :id")
    void touchAccess(@Param("id") Long id);

    long countByTenantId(String tenantId);

    @Query("SELECT e FROM AgentEpisodeEntity e WHERE e.sessionId = :sessionId ORDER BY e.createdAt")
    List<AgentEpisodeEntity> findBySessionId(@Param("sessionId") String sessionId);
}
