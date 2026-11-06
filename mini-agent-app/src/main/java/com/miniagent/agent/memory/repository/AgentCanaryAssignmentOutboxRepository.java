package com.miniagent.agent.memory.repository;

import com.miniagent.agent.memory.entity.AgentCanaryAssignmentOutboxEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AgentCanaryAssignmentOutboxRepository extends JpaRepository<AgentCanaryAssignmentOutboxEntity, Long> {
    long countByStatus(AgentCanaryAssignmentOutboxEntity.Status status);
    Optional<AgentCanaryAssignmentOutboxEntity> findByTenantIdAndUserIdAndSessionIdAndTaskIdAndNodeIdAndProcedureIdAndProposalIdAndEventType(
            String tenantId, Long userId, String sessionId, Long taskId, String nodeId, Long procedureId,
            Long proposalId, String eventType);
    List<AgentCanaryAssignmentOutboxEntity> findTop50ByStatusAndEventTypeAndNextAttemptAtLessThanEqualOrderByIdAsc(
            AgentCanaryAssignmentOutboxEntity.Status status, String eventType, LocalDateTime now);
    @Query(value = "select o.* from agent_canary_assignment_outbox o "
            + "where o.status = :status and o.event_type = 'OBSERVATION' and o.next_attempt_at <= :now "
            + "and exists (select 1 from agent_canary_assignment_outbox a "
            + "where a.tenant_id=o.tenant_id and a.user_id=o.user_id and a.session_id=o.session_id "
            + "and a.task_id=o.task_id and a.node_id=o.node_id and a.procedure_id=o.procedure_id "
            + "and a.proposal_id=o.proposal_id and a.event_type='ASSIGNMENT' and a.status='DONE') "
            + "order by o.id asc limit 50", nativeQuery = true)
    List<AgentCanaryAssignmentOutboxEntity> findDeliverableObservations(
            @Param("status") String status, @Param("now") LocalDateTime now);
    @Modifying
    @Query("update AgentCanaryAssignmentOutboxEntity e set e.status=:processing,e.leaseUntil=:leaseUntil,e.claimToken=:claimToken "
            + "where e.id=:id and e.status=:pending and e.nextAttemptAt<=:now")
    int claim(@Param("id") Long id, @Param("pending") AgentCanaryAssignmentOutboxEntity.Status pending,
              @Param("processing") AgentCanaryAssignmentOutboxEntity.Status processing,
              @Param("now") LocalDateTime now, @Param("leaseUntil") LocalDateTime leaseUntil,
              @Param("claimToken") String claimToken);
    @Modifying
    @Query("update AgentCanaryAssignmentOutboxEntity e set e.status=:done,e.leaseUntil=null,e.claimToken=null,e.lastError=null "
            + "where e.id=:id and e.status=:processing and e.claimToken=:claimToken")
    int complete(@Param("id") Long id, @Param("processing") AgentCanaryAssignmentOutboxEntity.Status processing,
                 @Param("done") AgentCanaryAssignmentOutboxEntity.Status done, @Param("claimToken") String claimToken);
    @Modifying
    @Query("update AgentCanaryAssignmentOutboxEntity e set e.status=:status,e.attempts=:attempts,e.nextAttemptAt=:nextAttemptAt,"
            + "e.leaseUntil=null,e.claimToken=null,e.lastError=:lastError where e.id=:id and e.status=:processing and e.claimToken=:claimToken")
    int failAttempt(@Param("id") Long id, @Param("processing") AgentCanaryAssignmentOutboxEntity.Status processing,
                    @Param("status") AgentCanaryAssignmentOutboxEntity.Status status,
                    @Param("attempts") int attempts, @Param("nextAttemptAt") LocalDateTime nextAttemptAt,
                    @Param("lastError") String lastError, @Param("claimToken") String claimToken);
    @Modifying
    @Query("update AgentCanaryAssignmentOutboxEntity e set e.status=:pending,e.leaseUntil=null,e.claimToken=null,e.nextAttemptAt=:now "
            + "where e.status=:processing and e.leaseUntil<:now")
    int recoverExpired(@Param("processing") AgentCanaryAssignmentOutboxEntity.Status processing,
                       @Param("pending") AgentCanaryAssignmentOutboxEntity.Status pending,
                       @Param("now") LocalDateTime now);
}
