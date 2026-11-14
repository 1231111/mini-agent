package com.miniagent.config.repository;

import com.miniagent.config.entity.AgentSessionPlanner;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AgentSessionPlannerRepository extends JpaRepository<AgentSessionPlanner, String> {

    List<AgentSessionPlanner> findBySessionIdOrSessionIdStartingWith(String sessionId, String scopePrefix);

    /** Scalar projection reads the database state even if an enclosing JPA context cached the entity. */
    interface LockedStream {
        long getPlannerVersion();
        String getExecutionId();
        String getEventsJson();
    }

    @Query(value = """
            SELECT planner_version AS plannerVersion, execution_id AS executionId, events_json AS eventsJson
            FROM agent_session_planner WHERE session_id = :sessionId FOR UPDATE
            """, nativeQuery = true)
    Optional<LockedStream> lockStream(@Param("sessionId") String sessionId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE AgentSessionPlanner p
            SET p.stateJson = :stateJson,
                p.eventsJson = :eventsJson,
                p.plannerVersion = :newVersion,
                p.updatedAt = :updatedAt
            WHERE p.sessionId = :sessionId AND p.plannerVersion = :expectedVersion
              AND (p.executionId = :expectedExecutionId OR (p.executionId IS NULL AND :expectedExecutionId IS NULL))
            """)
    int casUpdate(@Param("sessionId") String sessionId,
                  @Param("expectedVersion") long expectedVersion,
                  @Param("expectedExecutionId") String expectedExecutionId,
                  @Param("newVersion") long newVersion,
                  @Param("stateJson") String stateJson,
                  @Param("eventsJson") String eventsJson,
                  @Param("updatedAt") LocalDateTime updatedAt);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE AgentSessionPlanner p
            SET p.eventsJson = :eventsJson, p.updatedAt = :updatedAt
            WHERE p.sessionId = :sessionId AND p.plannerVersion = :expectedVersion
              AND (p.executionId = :expectedExecutionId OR (p.executionId IS NULL AND :expectedExecutionId IS NULL))
            """)
    int updateEvents(@Param("sessionId") String sessionId,
                     @Param("expectedVersion") long expectedVersion,
                     @Param("expectedExecutionId") String expectedExecutionId,
                     @Param("eventsJson") String eventsJson,
                     @Param("updatedAt") LocalDateTime updatedAt);
}
