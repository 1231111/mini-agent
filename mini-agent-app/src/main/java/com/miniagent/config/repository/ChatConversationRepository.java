package com.miniagent.config.repository;

import com.miniagent.config.entity.ChatConversation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface ChatConversationRepository extends JpaRepository<ChatConversation, String> {
    List<ChatConversation> findByUserIdAndDeletedFalseOrderByUpdatedAtDesc(Long userId);
    @Query(value = "select c.id, c.title, c.createdAt, c.updatedAt, count(m.id) "
            + "from ChatConversation c left join ChatMessage m on m.conversationId = c.id "
            + "where c.userId = :userId and c.deleted = false "
            + "group by c.id, c.title, c.createdAt, c.updatedAt order by c.updatedAt desc",
            countQuery = "select count(c) from ChatConversation c where c.userId = :userId and c.deleted = false")
    Page<Object[]> findUserSummaries(@Param("userId") Long userId, Pageable pageable);
    @Query(value = "select c.id, c.title, c.createdAt, c.updatedAt, count(m.id) "
            + "from ChatConversation c left join ChatMessage m on m.conversationId = c.id "
            + "where c.deleted = false "
            + "group by c.id, c.title, c.createdAt, c.updatedAt order by c.updatedAt desc",
            countQuery = "select count(c) from ChatConversation c where c.deleted = false")
    Page<Object[]> findAllSummaries(Pageable pageable);
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
