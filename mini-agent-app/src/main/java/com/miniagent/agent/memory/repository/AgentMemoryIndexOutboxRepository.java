package com.miniagent.agent.memory.repository;

import com.miniagent.agent.memory.entity.AgentMemoryIndexOutboxEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.time.LocalDateTime;
import java.util.List;

public interface AgentMemoryIndexOutboxRepository
        extends JpaRepository<AgentMemoryIndexOutboxEntity, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<AgentMemoryIndexOutboxEntity>
    findTop50ByStatusAndNextAttemptAtLessThanEqualOrderByIdAsc(
            AgentMemoryIndexOutboxEntity.Status status, LocalDateTime nextAttemptAt);

    List<AgentMemoryIndexOutboxEntity> findByStatus(AgentMemoryIndexOutboxEntity.Status status);
}
