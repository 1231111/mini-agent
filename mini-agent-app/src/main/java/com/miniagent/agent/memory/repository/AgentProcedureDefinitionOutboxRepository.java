package com.miniagent.agent.memory.repository;

import com.miniagent.agent.memory.entity.AgentProcedureDefinitionOutboxEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AgentProcedureDefinitionOutboxRepository
        extends JpaRepository<AgentProcedureDefinitionOutboxEntity, Long> {

    /**
     * 取一批到期的待投递记录。
     *
     * <p>刻意不加 {@code @Lock(PESSIMISTIC_WRITE)}：本方法在无事务的调度方法里调用，
     * 悲观锁要求活跃事务，加了会让每次调度都抛 {@code Query requires transaction be in progress}，
     * 队列一条都投不出去（{@code MemoryIndexOutboxService} 上踩过这个坑）。跨实例互斥交给
     * {@code MemoryTaskLock}。</p>
     */
    List<AgentProcedureDefinitionOutboxEntity> findTop50ByStatusAndNextAttemptAtLessThanEqualOrderByIdAsc(
            AgentProcedureDefinitionOutboxEntity.Status status, LocalDateTime now);

    List<AgentProcedureDefinitionOutboxEntity> findByStatus(AgentProcedureDefinitionOutboxEntity.Status status);

    Optional<AgentProcedureDefinitionOutboxEntity> findByTenantIdAndProcedureIdAndProcedureVersion(
            String tenantId, Long procedureId, Integer procedureVersion);
}
