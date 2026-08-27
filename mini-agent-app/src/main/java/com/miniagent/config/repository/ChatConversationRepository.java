package com.miniagent.config.repository;

import com.miniagent.config.entity.ChatConversation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;

public interface ChatConversationRepository extends JpaRepository<ChatConversation, String> {
    List<ChatConversation> findByUserIdAndDeletedFalseOrderByUpdatedAtDesc(Long userId);
    long countByUserId(Long userId);

    java.util.Optional<ChatConversation> findByIdAndUserId(String id, Long userId);

    java.util.Optional<ChatConversation> findByIdAndUserIdAndDeletedFalse(String id, Long userId);

    boolean existsByIdAndUserId(String id, Long userId);

    boolean existsByIdAndUserIdAndDeletedFalse(String id, Long userId);

    /** 所有去重的用户 ID（供每日分析按用户遍历）。 */
    @Query("select distinct c.userId from ChatConversation c where c.deleted = false")
    List<Long> findDistinctUserIds();

    /** 某用户在给定时间点之后有更新的会话（当天活跃会话）。 */
    List<ChatConversation> findByUserIdAndDeletedFalseAndUpdatedAtGreaterThanEqualOrderByUpdatedAtDesc(
            Long userId, Long since);
}
