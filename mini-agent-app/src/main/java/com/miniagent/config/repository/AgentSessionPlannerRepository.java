package com.miniagent.config.repository;

import com.miniagent.config.entity.AgentSessionPlanner;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

public interface AgentSessionPlannerRepository extends JpaRepository<AgentSessionPlanner, String> {

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE AgentSessionPlanner p
            SET p.stateJson = :stateJson,
                p.eventsJson = :eventsJson,
                p.plannerVersion = :newVersion,
                p.updatedAt = :updatedAt
            WHERE p.sessionId = :sessionId AND p.plannerVersion = :expectedVersion
            """)
    int casUpdate(@Param("sessionId") String sessionId,
                  @Param("expectedVersion") long expectedVersion,
                  @Param("newVersion") long newVersion,
                  @Param("stateJson") String stateJson,
                  @Param("eventsJson") String eventsJson,
                  @Param("updatedAt") LocalDateTime updatedAt);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE AgentSessionPlanner p
            SET p.eventsJson = :eventsJson, p.updatedAt = :updatedAt
            WHERE p.sessionId = :sessionId AND p.plannerVersion = :expectedVersion
            """)
    int updateEvents(@Param("sessionId") String sessionId,
                     @Param("expectedVersion") long expectedVersion,
                     @Param("eventsJson") String eventsJson,
                     @Param("updatedAt") LocalDateTime updatedAt);
}
