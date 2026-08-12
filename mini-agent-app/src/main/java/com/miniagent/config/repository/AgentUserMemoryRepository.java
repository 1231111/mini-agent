package com.miniagent.config.repository;

import com.miniagent.config.entity.AgentUserMemory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentUserMemoryRepository extends JpaRepository<AgentUserMemory, Long> {
}
