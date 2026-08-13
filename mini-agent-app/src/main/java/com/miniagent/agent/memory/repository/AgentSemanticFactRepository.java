package com.miniagent.agent.memory.repository;

import com.miniagent.agent.memory.entity.AgentMemoryEntryEntity;
import com.miniagent.agent.memory.entity.AgentSemanticFactEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AgentSemanticFactRepository extends JpaRepository<AgentSemanticFactEntity, Long> {

    List<AgentSemanticFactEntity> findByTenantIdAndScopeTypeAndScopeId(
        String tenantId, AgentMemoryEntryEntity.ScopeType scopeType, String scopeId);

    List<AgentSemanticFactEntity> findByTenantIdAndScopeTypeAndScopeIdAndSubject(
        String tenantId, AgentMemoryEntryEntity.ScopeType scopeType,
        String scopeId, String subject);

    @Query("SELECT f FROM AgentSemanticFactEntity f WHERE f.tenantId = :tenantId " +
           "AND f.scopeType = :scopeType AND f.scopeId = :scopeId " +
           "AND f.supersededBy IS NULL AND f.validTo IS NULL")
    List<AgentSemanticFactEntity> findActiveFacts(
        @Param("tenantId") String tenantId,
        @Param("scopeType") AgentMemoryEntryEntity.ScopeType scopeType,
        @Param("scopeId") String scopeId);

    @Query("SELECT f FROM AgentSemanticFactEntity f WHERE f.tenantId = :tenantId " +
           "AND f.subject = :subject AND f.predicate = :predicate " +
           "AND f.supersededBy IS NULL AND f.validTo IS NULL")
    List<AgentSemanticFactEntity> findActiveByTriple(
        @Param("tenantId") String tenantId,
        @Param("subject") String subject,
        @Param("predicate") String predicate);

    long countByTenantId(String tenantId);
}
