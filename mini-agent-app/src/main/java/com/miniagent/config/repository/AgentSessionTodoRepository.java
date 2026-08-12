package com.miniagent.config.repository;

import com.miniagent.config.entity.AgentSessionTodo;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentSessionTodoRepository extends JpaRepository<AgentSessionTodo, String> {
}
