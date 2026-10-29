package com.miniagent.agent.memory.repository;

import com.miniagent.agent.memory.entity.AgentProjectEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.Optional;

public interface AgentProjectRepository extends JpaRepository<AgentProjectEntity, String> {
    Optional<AgentProjectEntity> findByProjectIdAndTenantId(String projectId, String tenantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from AgentProjectEntity p where p.projectId = :projectId and p.tenantId = :tenantId")
    Optional<AgentProjectEntity> findForUpdate(@Param("projectId") String projectId,
                                                @Param("tenantId") String tenantId);
}
