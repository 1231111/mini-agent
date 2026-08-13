package com.miniagent.agent.memory.repository;

import com.miniagent.agent.memory.entity.AgentMemoryEntryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AgentMemoryEntryRepository extends JpaRepository<AgentMemoryEntryEntity, Long> {

    List<AgentMemoryEntryEntity> findByTenantIdAndStatus(
        String tenantId, AgentMemoryEntryEntity.Status status);

    List<AgentMemoryEntryEntity> findByTenantIdAndScopeTypeAndScopeIdAndStatus(
        String tenantId, AgentMemoryEntryEntity.ScopeType scopeType,
        String scopeId, AgentMemoryEntryEntity.Status status);

    List<AgentMemoryEntryEntity> findByTenantIdAndScopeTypeAndScopeIdAndMemoryTypeAndStatus(
        String tenantId, AgentMemoryEntryEntity.ScopeType scopeType,
        String scopeId, AgentMemoryEntryEntity.MemoryType memoryType,
        AgentMemoryEntryEntity.Status status);

    @Query("SELECT e FROM AgentMemoryEntryEntity e WHERE e.tenantId = :tenantId " +
           "AND e.status = 'ACTIVE' AND e.content LIKE %:keyword%")
    List<AgentMemoryEntryEntity> searchByKeyword(@Param("tenantId") String tenantId,
                                                  @Param("keyword") String keyword);

    @Modifying
    @Query("UPDATE AgentMemoryEntryEntity e SET e.accessCount = e.accessCount + 1, " +
           "e.lastAccessedAt = CURRENT_TIMESTAMP WHERE e.id = :id")
    void touchAccess(@Param("id") Long id);

    long countByTenantIdAndStatus(String tenantId, AgentMemoryEntryEntity.Status status);

    long countByTenantIdAndScopeTypeAndScopeId(String tenantId,
        AgentMemoryEntryEntity.ScopeType scopeType, String scopeId);

    @Query("SELECT e.memoryType, COUNT(e) FROM AgentMemoryEntryEntity e " +
           "WHERE e.tenantId = :tenantId AND e.status = 'ACTIVE' GROUP BY e.memoryType")
    List<Object[]> countByType(@Param("tenantId") String tenantId);

    List<AgentMemoryEntryEntity> findByTenantIdAndStatusAndImportanceLessThan(
        String tenantId, AgentMemoryEntryEntity.Status status, double importance);
}
