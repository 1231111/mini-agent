package com.miniagent.config.repository;

import com.miniagent.config.entity.ChatTask;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface ChatTaskRepository extends JpaRepository<ChatTask, Long> {
    Page<ChatTask> findByUserIdAndSessionIdAndDeletedFalseOrderByCreatedAtDesc(
            Long userId, String sessionId, Pageable pageable);
    long countByUserIdAndSessionIdAndDeletedFalse(Long userId, String sessionId);
    @Query("SELECT t FROM ChatTask t WHERE t.userId = :userId AND t.deleted = false "
            + "AND t.id IN (SELECT MAX(t2.id) FROM ChatTask t2 WHERE t2.userId = :userId "
            + "AND t2.deleted = false GROUP BY t2.sessionId) ORDER BY t.createdAt DESC")
    List<ChatTask> findLatestTaskPerSession(Long userId);
    void deleteBySessionId(String sessionId);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ChatTask t set t.deleted = true, t.updatedAt = CURRENT_TIMESTAMP "
            + "where t.userId = :userId and t.sessionId = :sessionId and t.deleted = false")
    int softDeleteByUserIdAndSessionId(@Param("userId") Long userId,
                                       @Param("sessionId") String sessionId);

    boolean existsByUserIdAndSessionIdAndDeletedFalse(Long userId, String sessionId);
}
