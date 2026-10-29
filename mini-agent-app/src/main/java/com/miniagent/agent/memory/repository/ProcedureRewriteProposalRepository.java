package com.miniagent.agent.memory.repository;

import com.miniagent.agent.memory.entity.ProcedureRewriteProposalEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;

public interface ProcedureRewriteProposalRepository extends JpaRepository<ProcedureRewriteProposalEntity, Long> {
    boolean existsByTenantIdAndProcedureIdAndBaseVersionAndEpisodeId(
            String tenantId, Long procedureId, Integer baseVersion, Long episodeId);
    List<ProcedureRewriteProposalEntity> findByTenantIdAndUserIdOrderByCreatedAtDesc(
            String tenantId, Long userId);
    Optional<ProcedureRewriteProposalEntity> findByIdAndTenantIdAndUserId(
            Long id, String tenantId, Long userId);
    Optional<ProcedureRewriteProposalEntity> findByIdAndTenantId(Long id, String tenantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from ProcedureRewriteProposalEntity p "
            + "where p.id = :id and p.tenantId = :tenant and p.userId = :user")
    Optional<ProcedureRewriteProposalEntity> findForReview(@Param("id") Long id,
            @Param("tenant") String tenantId, @Param("user") Long userId);
}
