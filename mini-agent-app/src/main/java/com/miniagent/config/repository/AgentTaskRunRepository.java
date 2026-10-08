package com.miniagent.config.repository;

import com.miniagent.config.entity.AgentTaskRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AgentTaskRunRepository extends JpaRepository<AgentTaskRun, Long> {

    Optional<AgentTaskRun> findFirstBySessionIdAndStatusOrderByStartedAtDesc(
            String sessionId, AgentTaskRun.Status status);

    long countByUserIdAndStatus(Long userId, AgentTaskRun.Status status);

    List<AgentTaskRun> findByStatus(AgentTaskRun.Status status);

    boolean existsBySessionIdAndStatus(String sessionId, AgentTaskRun.Status status);

    /** 归属判定用：任务一 tryStart 就有行，比会话/ChatTask（跑完才写）早得多。 */
    boolean existsByUserIdAndSessionId(Long userId, String sessionId);

    @Modifying
    @Query("""
            update AgentTaskRun r
               set r.status = :status,
                   r.finishedAt = :finishedAt,
                   r.errorMessage = :error
             where r.id = :runId
               and r.status = :running
            """)
    int finishIfRunning(
            @Param("runId") Long runId,
            @Param("running") AgentTaskRun.Status running,
            @Param("status") AgentTaskRun.Status status,
            @Param("finishedAt") LocalDateTime finishedAt,
            @Param("error") String error);
}
