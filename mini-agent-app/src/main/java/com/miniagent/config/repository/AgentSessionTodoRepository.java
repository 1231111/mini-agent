package com.miniagent.config.repository;

import com.miniagent.config.entity.AgentSessionTodo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

public interface AgentSessionTodoRepository
        extends JpaRepository<AgentSessionTodo, String> {

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            update AgentSessionTodo state
               set state.currentTaskId = :currentTaskId,
                   state.pausedTaskId = :pausedTaskId,
                   state.scopeSequence = :scopeSequence,
                   state.version = coalesce(state.version, 0) + 1,
                   state.updatedAt = :updatedAt
             where state.sessionId = :sessionId
               and coalesce(state.version, 0) = :expectedVersion
            """)
    int compareAndSetScope(
            @Param("sessionId") String sessionId,
            @Param("expectedVersion") long expectedVersion,
            @Param("currentTaskId") long currentTaskId,
            @Param("pausedTaskId") Long pausedTaskId,
            @Param("scopeSequence") long scopeSequence,
            @Param("updatedAt") LocalDateTime updatedAt);
}
