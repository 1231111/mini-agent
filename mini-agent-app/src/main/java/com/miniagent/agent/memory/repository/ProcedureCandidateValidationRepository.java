package com.miniagent.agent.memory.repository;

import com.miniagent.agent.memory.entity.ProcedureCandidateValidationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProcedureCandidateValidationRepository
        extends JpaRepository<ProcedureCandidateValidationEntity, Long> {
    List<ProcedureCandidateValidationEntity> findByTenantIdAndProposalIdOrderByCreatedAtDesc(
            String tenantId, Long proposalId);
}
