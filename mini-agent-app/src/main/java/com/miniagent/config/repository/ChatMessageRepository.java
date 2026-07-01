package com.miniagent.config.repository;

import com.miniagent.config.entity.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.util.List;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {
    List<ChatMessage> findByConversationIdOrderByTimestampAsc(String conversationId);
    void deleteByConversationId(String conversationId);
    long countByConversationId(String conversationId);
    Page<ChatMessage> findByConversationIdOrderByTimestampDesc(String conversationId, Pageable pageable);
}
