package com.miniagent.agent.memory.repository;

import com.miniagent.agent.memory.entity.AgentProjectEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface AgentProjectRepository extends JpaRepository<AgentProjectEntity, String> {
    Optional<AgentProjectEntity> findByProjectIdAndTenantId(String projectId, String tenantId);
}
