package com.miniagent.agent.memory.repository;

import com.miniagent.agent.memory.entity.AgentProcedureRevisionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface AgentProcedureRevisionRepository extends JpaRepository<AgentProcedureRevisionEntity, Long> {
    Optional<AgentProcedureRevisionEntity> findByTenantIdAndProcedureIdAndProcedureVersion(
            String tenantId, Long procedureId, Integer version);
    List<AgentProcedureRevisionEntity> findByTenantIdAndProcedureIdOrderByProcedureVersionDesc(
            String tenantId, Long procedureId);
}
