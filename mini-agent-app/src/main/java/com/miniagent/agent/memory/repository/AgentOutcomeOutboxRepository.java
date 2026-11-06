package com.miniagent.agent.memory.repository;

import com.miniagent.agent.memory.entity.AgentOutcomeOutboxEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AgentOutcomeOutboxRepository extends JpaRepository<AgentOutcomeOutboxEntity, Long> {
    Optional<AgentOutcomeOutboxEntity> findByEventId(String eventId);

    List<AgentOutcomeOutboxEntity> findTop500ByStatusAndNextAttemptAtLessThanEqualOrderByIdAsc(
            AgentOutcomeOutboxEntity.Status status, LocalDateTime now);

    Optional<AgentOutcomeOutboxEntity> findFirstByStatusOrderByCreatedAtAsc(
            AgentOutcomeOutboxEntity.Status status);

    @Modifying
    @Query("update AgentOutcomeOutboxEntity e set e.status = :processing, e.leaseUntil = :leaseUntil,"
            + " e.claimToken = :claimToken where e.id = :id and e.status = :pending"
            + " and e.nextAttemptAt <= :now")
    int claim(@Param("id") Long id,
              @Param("pending") AgentOutcomeOutboxEntity.Status pending,
              @Param("processing") AgentOutcomeOutboxEntity.Status processing,
              @Param("now") LocalDateTime now,
              @Param("leaseUntil") LocalDateTime leaseUntil,
              @Param("claimToken") String claimToken);

    @Modifying
    @Query("update AgentOutcomeOutboxEntity e set e.status = :done, e.leaseUntil = null,"
            + " e.claimToken = null, e.lastError = null where e.id = :id"
            + " and e.status = :processing and e.claimToken = :claimToken")
    int complete(@Param("id") Long id,
                 @Param("processing") AgentOutcomeOutboxEntity.Status processing,
                 @Param("done") AgentOutcomeOutboxEntity.Status done,
                 @Param("claimToken") String claimToken);

    @Modifying
    @Query("update AgentOutcomeOutboxEntity e set e.status = :status, e.attempts = :attempts,"
            + " e.nextAttemptAt = :nextAttemptAt, e.leaseUntil = null, e.claimToken = null,"
            + " e.lastError = :lastError where e.id = :id and e.status = :processing"
            + " and e.claimToken = :claimToken")
    int failAttempt(@Param("id") Long id,
                    @Param("processing") AgentOutcomeOutboxEntity.Status processing,
                    @Param("status") AgentOutcomeOutboxEntity.Status status,
                    @Param("attempts") int attempts,
                    @Param("nextAttemptAt") LocalDateTime nextAttemptAt,
                    @Param("lastError") String lastError,
                    @Param("claimToken") String claimToken);

    @Modifying
    @Query("update AgentOutcomeOutboxEntity e set e.status = :pending, e.leaseUntil = null,"
            + " e.claimToken = null, e.nextAttemptAt = :now where e.status = :processing"
            + " and e.leaseUntil < :now")
    int recoverExpired(@Param("processing") AgentOutcomeOutboxEntity.Status processing,
                       @Param("pending") AgentOutcomeOutboxEntity.Status pending,
                       @Param("now") LocalDateTime now);
}
