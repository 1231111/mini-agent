package com.miniagent.config.repository;

import com.miniagent.config.entity.AgentTraceStep;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;

public interface AgentTraceStepRepository extends JpaRepository<AgentTraceStep, Long> {
    List<AgentTraceStep> findBySessionIdOrderByTurnIndexAscIdAsc(String sessionId);
    List<AgentTraceStep> findByExecutionIdOrderByTurnIndexAscIdAsc(String executionId);
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
    @Query("SELECT s.stepType, s.toolName, s.durationMs FROM AgentTraceStep s WHERE s.executionId = :executionId AND s.durationMs > 0 ORDER BY s.durationMs DESC")
    List<Object[]> slowestStepsByExecutionId(String executionId);
    void deleteBySessionId(String sessionId);
}
