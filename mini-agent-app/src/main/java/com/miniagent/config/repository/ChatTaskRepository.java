package com.miniagent.config.repository;

import com.miniagent.config.entity.ChatTask;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;

public interface ChatTaskRepository extends JpaRepository<ChatTask, Long> {
    Page<ChatTask> findByUserIdAndSessionIdOrderByCreatedAtDesc(Long userId, String sessionId, Pageable pageable);
    long countByUserIdAndSessionId(Long userId, String sessionId);
    @Query("SELECT t FROM ChatTask t WHERE t.userId = :userId AND t.id IN (SELECT MAX(t2.id) FROM ChatTask t2 WHERE t2.userId = :userId GROUP BY t2.sessionId) ORDER BY t.createdAt DESC")
    List<ChatTask> findLatestTaskPerSession(Long userId);
    void deleteBySessionId(String sessionId);
}
