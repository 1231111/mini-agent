package com.miniagent.config.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.config.entity.ChatConversation;
import com.miniagent.config.entity.ChatMessage;
import com.miniagent.config.repository.ChatConversationRepository;
import com.miniagent.config.repository.ChatMessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class DatabaseConversationStore {

    private static final Logger log = LoggerFactory.getLogger(DatabaseConversationStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ChatConversationRepository conversationRepo;
    private final ChatMessageRepository messageRepo;

    public DatabaseConversationStore(
            ChatConversationRepository conversationRepo,
            ChatMessageRepository messageRepo) {
        this.conversationRepo = conversationRepo;
        this.messageRepo = messageRepo;
    }

    /** Create a new conversation for a user */
    @Transactional
    public Conversation create(Long userId, String id, String title) {
        ChatConversation entity = new ChatConversation();
        entity.setId(id);
        entity.setUserId(userId);
        entity.setTitle(title == null || title.isBlank() ? "New Chat" : title.trim());
        long now = Instant.now().toEpochMilli();
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        conversationRepo.save(entity);
        return toModel(entity);
    }

    /** Get conversation by id */
    @Transactional(readOnly = true)
    public Conversation get(String id) {
        return conversationRepo.findById(id)
                .map(this::toModel)
                .orElse(null);
    }

    /** List conversations for a user */
    @Transactional(readOnly = true)
    public List<ConversationSummary> list(Long userId) {
        return conversationRepo.findByUserIdOrderByUpdatedAtDesc(userId).stream()
                .map(c -> new ConversationSummary(
                        c.getId(), c.getTitle(), c.getCreatedAt(), c.getUpdatedAt(),
                        messageRepo.countByConversationId(c.getId())))
                .collect(Collectors.toList());
    }

    /** 所有去重的用户 ID（供每日分析按用户遍历）。 */
    @Transactional(readOnly = true)
    public List<Long> listUserIds() {
        return conversationRepo.findDistinctUserIds();
    }

    /** 某用户在 since 之后有更新的会话（含完整消息），用于每日分析当天活跃会话。 */
    @Transactional(readOnly = true)
    public List<Conversation> activeConversationsSince(Long userId, long since) {
        return conversationRepo
                .findByUserIdAndUpdatedAtGreaterThanEqualOrderByUpdatedAtDesc(userId, since)
                .stream().map(this::toModel).collect(Collectors.toList());
    }

    /** List all conversations (no user filter, for backward compat) */
    @Transactional(readOnly = true)
    public List<ConversationSummary> listAll() {
        return conversationRepo.findAll().stream()
                .sorted((a, b) -> Long.compare(b.getUpdatedAt(), a.getUpdatedAt()))
                .map(c -> new ConversationSummary(
                        c.getId(), c.getTitle(), c.getCreatedAt(), c.getUpdatedAt(),
                        messageRepo.countByConversationId(c.getId())))
                .collect(Collectors.toList());
    }

    /** Append a message */
    @Transactional
    public void addMessage(String id, String role, String content) {
        conversationRepo.findById(id).ifPresent(conv -> {
            ChatMessage msg = new ChatMessage();
            msg.setConversationId(id);
            msg.setRole(role);
            msg.setContent(content);
            msg.setTimestamp(Instant.now().toEpochMilli());
            messageRepo.save(msg);

            long now = Instant.now().toEpochMilli();
            conv.setUpdatedAt(now);
            // Auto title from first user message
            if ("user".equals(role)) {
                long userMsgCount = messageRepo.findByConversationIdOrderByTimestampAsc(id)
                        .stream().filter(m -> "user".equals(m.getRole())).count();
                if (userMsgCount == 1) {
                    String autoTitle = content.length() > 40 ? content.substring(0, 40) + "..." : content;
                    conv.setTitle(autoTitle.replaceAll("[\r\n]", " ").trim());
                }
            }
            conversationRepo.save(conv);
        });
    }

    /** Append a message with images */
    @Transactional
    public void addMessageWithImages(String id, String role, String content, List<String> imagePaths) {
        conversationRepo.findById(id).ifPresent(conv -> {
            ChatMessage msg = new ChatMessage();
            msg.setConversationId(id);
            msg.setRole(role);
            msg.setContent(content);
            msg.setTimestamp(Instant.now().toEpochMilli());
            if (imagePaths != null && !imagePaths.isEmpty()) {
                try {
                    msg.setImages(MAPPER.writeValueAsString(imagePaths));
                } catch (Exception e) {
                    log.warn("Failed to serialize images: {}", e.getMessage());
                }
            }
            messageRepo.save(msg);

            long now = Instant.now().toEpochMilli();
            conv.setUpdatedAt(now);
            if ("user".equals(role)) {
                long userMsgCount = messageRepo.findByConversationIdOrderByTimestampAsc(id)
                        .stream().filter(m -> "user".equals(m.getRole())).count();
                if (userMsgCount == 1) {
                    String autoTitle = content.length() > 40 ? content.substring(0, 40) + "..." : content;
                    conv.setTitle(autoTitle.replaceAll("[\r\n]", " ").trim());
                }
            }
            conversationRepo.save(conv);
        });
    }

    /** Rename */
    @Transactional
    public boolean rename(String id, String newTitle) {
        return conversationRepo.findById(id).map(conv -> {
            conv.setTitle(newTitle.trim());
            conv.setUpdatedAt(Instant.now().toEpochMilli());
            conversationRepo.save(conv);
            return true;
        }).orElse(false);
    }

    /** Delete */
    @Transactional
    public boolean delete(String id) {
        if (conversationRepo.existsById(id)) {
            messageRepo.deleteByConversationId(id);
            conversationRepo.deleteById(id);
            return true;
        }
        return false;
    }

    /** Exists */
    public boolean exists(String id) {
        return conversationRepo.existsById(id);
    }

    /** Convert entity to model */
    private Conversation toModel(ChatConversation entity) {
        Conversation conv = new Conversation();
        conv.id = entity.getId();
        conv.title = entity.getTitle();
        conv.createdAt = entity.getCreatedAt();
        conv.updatedAt = entity.getUpdatedAt();
        conv.messages = messageRepo.findByConversationIdOrderByTimestampAsc(entity.getId())
                .stream().map(m -> {
            Message msg = new Message();
            msg.role = m.getRole();
            msg.content = m.getContent();
            msg.timestamp = m.getTimestamp();
            if (m.getImages() != null) {
                try {
                    msg.images = MAPPER.readValue(m.getImages(), new TypeReference<List<String>>() {});
                } catch (Exception e) {
                    msg.images = null;
                }
            }
            return msg;
        }).collect(Collectors.toList());
        return conv;
    }

    // =========================================================================
    // Data classes (same as ConversationStore for compatibility)
    // =========================================================================

    public static class Conversation {
        public String id;
        public String title;
        public long createdAt;
        public long updatedAt;
        public List<Message> messages = new ArrayList<>();
    }

    public static class Message {
        public String role;
        public String content;
        public long timestamp;
        public List<String> images;
    }

    public static class ConversationSummary {
        public String id;
        public String title;
        public long createdAt;
        public long updatedAt;
        public long messageCount;

        public ConversationSummary(String id, String title, long createdAt, long updatedAt, long messageCount) {
            this.id = id;
            this.title = title;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
            this.messageCount = messageCount;
        }
    }
}
