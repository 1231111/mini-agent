package com.miniagent.agent.memory.repository;

import com.miniagent.agent.memory.entity.AgentEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AgentEventRepository extends JpaRepository<AgentEventEntity, Long> {

    List<AgentEventEntity> findBySessionIdOrderByCreatedAtAsc(String sessionId);

    List<AgentEventEntity> findByProcessedFalseOrderByCreatedAtAsc();

    @Modifying
    @Query("UPDATE AgentEventEntity e SET e.processed = true WHERE e.id IN :ids")
    void markProcessed(@Param("ids") List<Long> ids);

    long countByTenantId(String tenantId);

    long countByProcessedFalse();
}
