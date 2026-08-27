package com.miniagent.agent.memory.repository;

import com.miniagent.agent.memory.entity.AgentMemoryEmbeddingEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentMemoryEmbeddingRepository extends JpaRepository<AgentMemoryEmbeddingEntity, Long> {

    List<AgentMemoryEmbeddingEntity> findByMemoryType(AgentMemoryEmbeddingEntity.EmbeddingMemoryType memoryType);
}
