package com.miniagent.agent.memory.repository;

import com.miniagent.agent.memory.entity.AgentWorkingMemoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentWorkingMemoryRepository extends JpaRepository<AgentWorkingMemoryEntity, String> {

    List<AgentWorkingMemoryEntity> findByTenantIdAndStatus(String tenantId, String status);
}
