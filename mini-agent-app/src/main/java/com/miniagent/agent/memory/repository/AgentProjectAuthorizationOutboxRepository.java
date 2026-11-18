package com.miniagent.agent.memory.repository;

import com.miniagent.agent.memory.entity.AgentProjectAuthorizationOutboxEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AgentProjectAuthorizationOutboxRepository
        extends JpaRepository<AgentProjectAuthorizationOutboxEntity, Long> {
    Optional<AgentProjectAuthorizationOutboxEntity> findByTenantIdAndProjectIdAndAuthorizationVersion(
            String tenantId, String projectId, Long authorizationVersion);
    List<AgentProjectAuthorizationOutboxEntity> findTop50ByStatusAndNextAttemptAtLessThanEqualOrderByIdAsc(
            AgentProjectAuthorizationOutboxEntity.Status status, LocalDateTime now);
    /** Requeue only abandoned work; a live worker may legitimately remain PROCESSING. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE AgentProjectAuthorizationOutboxEntity item "
            + "SET item.status = :pending, item.nextAttemptAt = :now, item.updatedAt = :now "
            + "WHERE item.status = :processing AND item.updatedAt < :staleBefore")
    int requeueStaleProcessing(@Param("processing") AgentProjectAuthorizationOutboxEntity.Status processing,
                               @Param("pending") AgentProjectAuthorizationOutboxEntity.Status pending,
                               @Param("staleBefore") LocalDateTime staleBefore,
                               @Param("now") LocalDateTime now);
}
