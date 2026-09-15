package com.miniagent.config.repository;

import com.miniagent.config.entity.AgentTaskRun;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
