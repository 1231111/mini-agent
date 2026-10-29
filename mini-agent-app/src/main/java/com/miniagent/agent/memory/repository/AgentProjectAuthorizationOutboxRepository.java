package com.miniagent.agent.memory.repository;

import com.miniagent.agent.memory.entity.AgentProjectAuthorizationOutboxEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AgentProjectAuthorizationOutboxRepository
        extends JpaRepository<AgentProjectAuthorizationOutboxEntity, Long> {
    Optional<AgentProjectAuthorizationOutboxEntity> findByTenantIdAndProjectIdAndAuthorizationVersion(
            String tenantId, String projectId, Long authorizationVersion);
    List<AgentProjectAuthorizationOutboxEntity> findTop50ByStatusAndNextAttemptAtLessThanEqualOrderByIdAsc(
            AgentProjectAuthorizationOutboxEntity.Status status, LocalDateTime now);
    List<AgentProjectAuthorizationOutboxEntity> findByStatus(AgentProjectAuthorizationOutboxEntity.Status status);
}
