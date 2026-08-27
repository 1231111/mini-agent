package com.miniagent.agent.memory.repository;

import com.miniagent.agent.memory.entity.AgentMemoryEntryEntity;
import com.miniagent.agent.memory.entity.AgentProcedureEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentProcedureRepository extends JpaRepository<AgentProcedureEntity, Long> {

    List<AgentProcedureEntity> findByTenantIdAndScopeTypeAndScopeIdAndStatus(
        String tenantId, AgentMemoryEntryEntity.ScopeType scopeType,
        String scopeId, AgentMemoryEntryEntity.Status status);

    List<AgentProcedureEntity> findByTenantIdAndScopeTypeAndScopeIdAndNameContainingAndStatus(
        String tenantId, AgentMemoryEntryEntity.ScopeType scopeType,
        String scopeId, String name, AgentMemoryEntryEntity.Status status);

    long countByTenantId(String tenantId);
}
