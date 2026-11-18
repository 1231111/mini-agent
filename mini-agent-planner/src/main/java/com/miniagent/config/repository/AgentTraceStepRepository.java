package com.miniagent.config.repository;

import com.miniagent.config.entity.AgentTraceStep;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.time.LocalDateTime;

public interface AgentTraceStepRepository extends JpaRepository<AgentTraceStep, Long> {
    List<AgentTraceStep> findBySessionIdOrderByTurnIndexAscIdAsc(String sessionId);
    List<AgentTraceStep> findByExecutionIdOrderByTurnIndexAscIdAsc(String executionId);
    Page<AgentTraceStep> findBySessionIdOrderByTurnIndexAscIdAsc(String sessionId, Pageable pageable);
    Page<AgentTraceStep> findByExecutionIdOrderByTurnIndexAscIdAsc(String executionId, Pageable pageable);
    List<AgentTraceStep> findTop500BySessionIdOrderByIdDesc(String sessionId);
    @Query(value = "SELECT DISTINCT s.sessionId FROM AgentTraceStep s WHERE s.executionId = :executionId",
            countQuery = "SELECT COUNT(DISTINCT s.sessionId) FROM AgentTraceStep s WHERE s.executionId = :executionId")
    Page<String> findDistinctSessionIdsByExecutionId(@Param("executionId") String executionId, Pageable pageable);
    long countBySessionId(String sessionId);
    long countByExecutionId(String executionId);
    @Query("SELECT COUNT(DISTINCT s.turnIndex) FROM AgentTraceStep s WHERE s.sessionId = :sessionId")
    long countDistinctTurnsBySessionId(String sessionId);
    @Query("SELECT COUNT(DISTINCT s.turnIndex) FROM AgentTraceStep s WHERE s.executionId = :executionId")
    long countDistinctTurnsByExecutionId(String executionId);
    @Query("SELECT SUM(s.durationMs) FROM AgentTraceStep s WHERE s.sessionId = :sessionId AND s.stepType = 'TOOL_RESULT'")
    Long sumDurationBySessionId(String sessionId);
    @Query("SELECT SUM(s.durationMs) FROM AgentTraceStep s WHERE s.executionId = :executionId AND s.stepType IN ('TOOL_RESULT', 'LLM_CALL')")
    Long sumDurationByExecutionId(String executionId);
    @Query("SELECT s.toolName, COUNT(s), AVG(s.durationMs) FROM AgentTraceStep s WHERE s.sessionId = :sessionId AND s.stepType = 'TOOL_RESULT' GROUP BY s.toolName")
    List<Object[]> toolStatsBySessionId(String sessionId);
    @Query("SELECT s.toolName, COUNT(s), AVG(s.durationMs) FROM AgentTraceStep s WHERE s.executionId = :executionId AND s.stepType = 'TOOL_RESULT' GROUP BY s.toolName")
    List<Object[]> toolStatsByExecutionId(String executionId);
    @Query("SELECT s.stepType, s.toolName, s.durationMs FROM AgentTraceStep s "
            + "WHERE s.executionId = :executionId AND s.durationMs > 0 ORDER BY s.durationMs DESC")
    List<Object[]> slowestStepsPageByExecutionId(@Param("executionId") String executionId, Pageable pageable);
    void deleteBySessionId(String sessionId);

    /**
     * Bound trace storage by age. Trace rows are operational evidence rather
     * than the durable conversation record, so retention is intentionally
     * enforced at the database boundary instead of relying on application
     * memory or an unbounded log file.
     */
    long deleteByCreatedAtBefore(LocalDateTime cutoff);
}
