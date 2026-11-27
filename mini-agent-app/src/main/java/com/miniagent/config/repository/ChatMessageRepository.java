package com.miniagent.config.repository;

import com.miniagent.config.entity.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.util.List;
import java.util.Optional;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {
    List<ChatMessage> findByConversationIdOrderByTimestampAsc(String conversationId);
    Page<ChatMessage> findByConversationIdOrderByTimestampAsc(String conversationId, Pageable pageable);
    void deleteByConversationId(String conversationId);
    long countByConversationId(String conversationId);
    Page<ChatMessage> findByConversationIdOrderByTimestampDesc(String conversationId, Pageable pageable);
    Page<ChatMessage> findByConversationIdOrderByTimestampDescIdDesc(String conversationId, Pageable pageable);

    Optional<ChatMessage> findFirstByConversationIdAndRoleAndContentOrderByTimestampDescIdDesc(
            String conversationId, String role, String content);

    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query("update ChatMessage m set m.content = :next "
            + "where m.id = :id and m.conversationId = :conversationId and m.role = :role "
            + "and ((m.content = :expected) or (m.content is null and :expected is null))")
    @org.springframework.transaction.annotation.Transactional
    int compareAndSetContent(@org.springframework.data.repository.query.Param("id") Long id,
                             @org.springframework.data.repository.query.Param("conversationId") String conversationId,
                             @org.springframework.data.repository.query.Param("role") String role,
                             @org.springframework.data.repository.query.Param("expected") String expected,
                             @org.springframework.data.repository.query.Param("next") String next);

    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query("update ChatMessage m set m.content = :next, "
            + "m.toolHistoryJson = :nextHistory where m.id = :id and m.conversationId = :conversationId "
            + "and m.role = :role and ((m.content = :expected) or (m.content is null and :expected is null)) "
            + "and ((m.toolHistoryJson = :expectedHistory) or (m.toolHistoryJson is null and :expectedHistory is null))")
    @org.springframework.transaction.annotation.Transactional
    int compareAndSetContentAndHistory(@org.springframework.data.repository.query.Param("id") Long id,
                                       @org.springframework.data.repository.query.Param("conversationId") String conversationId,
                                       @org.springframework.data.repository.query.Param("role") String role,
                                       @org.springframework.data.repository.query.Param("expected") String expected,
                                       @org.springframework.data.repository.query.Param("expectedHistory") String expectedHistory,
                                       @org.springframework.data.repository.query.Param("next") String next,
                                       @org.springframework.data.repository.query.Param("nextHistory") String nextHistory);
}
