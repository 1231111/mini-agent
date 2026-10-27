package com.miniagent.config.service;

import org.springframework.beans.factory.annotation.Autowired;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.common.ChatRole;
import com.miniagent.common.MessageConstants;
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
import org.apache.commons.lang3.StringUtils;

@Service
public class DatabaseConversationStore {

    private static final Logger log = LoggerFactory.getLogger(DatabaseConversationStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired

    private ChatConversationRepository conversationRepo;
    @Autowired

    private ChatMessageRepository messageRepo;

    

    private static final int TITLE_MAX = 200;

    /** Create a new conversation for a user */
    @Transactional
    public Conversation create(Long userId, String id, String title) {
        ChatConversation entity = new ChatConversation();
        entity.setId(id);
        entity.setUserId(userId);
        entity.setTitle(truncateTitle(title));
        long now = Instant.now().toEpochMilli();
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        entity.setDeleted(false);
        conversationRepo.save(entity);
        return toModel(entity);
    }

    /**
     * Claim a session namespace atomically at the application boundary. A deleted
     * namespace remains reserved for its original owner and cannot be hijacked.
     */
    @Transactional
    public boolean claim(Long userId, String id, String title) {
        if (userId == null || id == null || id.isBlank()) {
            return false;
        }
        Optional<ChatConversation> existing = conversationRepo.findById(id);
        if (existing.isPresent()) {
            ChatConversation conversation = existing.get();
            if (!Objects.equals(userId, conversation.getUserId())) {
                return false;
            }
            if (conversation.isDeleted()) {
                conversation.setDeleted(false);
            }
            if (StringUtils.isNotBlank(title)) {
                conversation.setTitle(truncateTitle(title));
            }
            conversation.setUpdatedAt(Instant.now().toEpochMilli());
            conversationRepo.save(conversation);
            return true;
        }
        create(userId, id, title);
        return true;
    }

    /** DB title 列 length=255；超长用户消息（整库 schema）必须截断 */
    /**
     * 用**第一条真实用户消息**给会话定标题。
     *
     * <p>控制语（前端拼的「【系统】已批准…」「【系统】继续执行已确认的步骤 #N…」）
     * 在后端也是 {@code role=user} 存库的 —— 那是对的，它要驱动下一轮对话 ——
     * 但它们不是用户说的话，不该拿来当标题。此前判据是「第几条 user 消息」，
     * 于是首条即控制语时标题就成了那句批注（2026-10-15 实测存在这样的会话），
     * 而且后续真实的用户消息因为计数已 >1 永远补不上标题。</p>
     *
     * <p>所以两件事都要改：控制语不参与触发；计数也只数真实用户消息。</p>
     */
    private void applyAutoTitle(ChatConversation conv, String id, String role, String content) {
        if (!ChatRole.USER.getValue().equals(role)
                || MessageConstants.isSystemControlMessage(content)) {
            return;
        }
        long realUserMsgs = messageRepo.findByConversationIdOrderByTimestampAsc(id).stream()
                .filter(m -> ChatRole.USER.getValue().equals(m.getRole()))
                .filter(m -> !MessageConstants.isSystemControlMessage(m.getContent()))
                .count();
        if (realUserMsgs == 1) {
            conv.setTitle(truncateTitle(
                    content.length() > 40 ? content.substring(0, 40) + "..." : content));
        }
    }

    static String truncateTitle(String title) {
        if (StringUtils.isBlank(title)) {
            return "New Chat";
        }
        String oneLine = title.replaceAll("[\\r\\n]+", " ").trim();
        if (oneLine.length() <= TITLE_MAX) {
            return oneLine;
        }
        return oneLine.substring(0, TITLE_MAX - 3) + "...";
    }

    /** Get conversation by id */
    @Transactional(readOnly = true)
    public Conversation get(String id) {
        return conversationRepo.findById(id)
                .filter(c -> !c.isDeleted())
                .map(this::toModel)
                .orElse(null);
    }

    /** List conversations for a user */
    @Transactional(readOnly = true)
    public List<ConversationSummary> list(Long userId) {
        return conversationRepo.findByUserIdAndDeletedFalseOrderByUpdatedAtDesc(userId).stream()
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
                .findByUserIdAndDeletedFalseAndUpdatedAtGreaterThanEqualOrderByUpdatedAtDesc(userId, since)
                .stream().map(this::toModel).collect(Collectors.toList());
    }

    /** List all conversations (no user filter, for backward compat) */
    @Transactional(readOnly = true)
    public List<ConversationSummary> listAll() {
        return conversationRepo.findAll().stream()
                .filter(c -> !c.isDeleted())
                .sorted((a, b) -> Long.compare(b.getUpdatedAt(), a.getUpdatedAt()))
                .map(c -> new ConversationSummary(
                        c.getId(), c.getTitle(), c.getCreatedAt(), c.getUpdatedAt(),
                        messageRepo.countByConversationId(c.getId())))
                .collect(Collectors.toList());
    }

    /** Append a message */
    @Transactional
    public void addMessage(String id, String role, String content) {
        conversationRepo.findById(id).filter(conv -> !conv.isDeleted()).ifPresent(conv -> {
            ChatMessage msg = new ChatMessage();
            msg.setConversationId(id);
            msg.setRole(role);
            msg.setContent(content);
            msg.setTimestamp(Instant.now().toEpochMilli());
            messageRepo.save(msg);

            long now = Instant.now().toEpochMilli();
            conv.setUpdatedAt(now);
            // Auto title from first *real* user message
            applyAutoTitle(conv, id, role, content);
            conversationRepo.save(conv);
        });
    }

    /** Append a message with images */
    @Transactional
    public void addMessageWithImages(String id, String role, String content, List<String> imagePaths) {
        conversationRepo.findById(id).filter(conv -> !conv.isDeleted()).ifPresent(conv -> {
            ChatMessage msg = new ChatMessage();
            msg.setConversationId(id);
            msg.setRole(role);
            msg.setContent(content);
            msg.setTimestamp(Instant.now().toEpochMilli());
            if (Objects.nonNull(imagePaths) && !imagePaths.isEmpty()) {
                try {
                    msg.setImages(MAPPER.writeValueAsString(imagePaths));
                } catch (Exception e) {
                    log.warn("Failed to serialize images: {}", e.getMessage());
                }
            }
            messageRepo.save(msg);

            long now = Instant.now().toEpochMilli();
            conv.setUpdatedAt(now);
            applyAutoTitle(conv, id, role, content);
            conversationRepo.save(conv);
        });
    }

    /** Rename */
    @Transactional
    public boolean rename(String id, String newTitle) {
        return conversationRepo.findById(id).filter(conv -> !conv.isDeleted()).map(conv -> {
            conv.setTitle(truncateTitle(newTitle));
            conv.setUpdatedAt(Instant.now().toEpochMilli());
            conversationRepo.save(conv);
            return true;
        }).orElse(false);
    }

    /**
     * 绑定/清除会话工作目录。传 {@code null} 表示恢复默认工作区。
     *
     * <p>只改这一列，不动 {@code updatedAt}：切换工作目录不是一次对话活动，
     * 否则侧栏"今天/昨天"分组会因为改了目录而把会话顶到最前面。</p>
     */
    @Transactional
    public boolean setWorkspaceRoot(Long userId, String id, String workspaceRoot) {
        if (Objects.isNull(userId) || StringUtils.isBlank(id)) {
            return false;
        }
        return conversationRepo.findByIdAndUserIdAndDeletedFalse(id, userId).map(conv -> {
            conv.setWorkspaceRoot(StringUtils.isBlank(workspaceRoot) ? null : workspaceRoot.trim());
            conversationRepo.save(conv);
            return true;
        }).orElse(false);
    }

    /** Soft delete. Messages are retained for audit/recovery and are hidden by the parent flag. */
    @Transactional
    public boolean delete(String id) {
        return conversationRepo.findById(id)
                .filter(conv -> !conv.isDeleted())
                .map(conv -> {
                    conv.setDeleted(true);
                    conv.setUpdatedAt(Instant.now().toEpochMilli());
                    conversationRepo.save(conv);
                    return true;
                }).orElse(false);
    }

    /** Delete only if owned by userId (IDOR protection). */
    @Transactional
    public boolean deleteForUser(Long userId, String id) {
        if (Objects.isNull(userId) || Objects.isNull(id)) {
            return false;
        }
        return conversationRepo.findByIdAndUserIdAndDeletedFalse(id, userId)
                .map(conv -> {
                    conv.setDeleted(true);
                    conv.setUpdatedAt(Instant.now().toEpochMilli());
                    conversationRepo.save(conv);
                    return true;
                }).orElse(false);
    }

    @Transactional(readOnly = true)
    public Conversation getForUser(Long userId, String id) {
        if (Objects.isNull(userId) || Objects.isNull(id)) {
            return null;
        }
        return conversationRepo.findByIdAndUserIdAndDeletedFalse(id, userId)
                .map(this::toModel)
                .orElse(null);
    }

    public boolean ownedBy(Long userId, String id) {
        return Objects.nonNull(userId) && Objects.nonNull(id)
                && conversationRepo.existsByIdAndUserIdAndDeletedFalse(id, userId);
    }

    /** Exists */
    public boolean exists(String id) {
        return conversationRepo.findById(id).filter(c -> !c.isDeleted()).isPresent();
    }

    /**
     * 会话归属的用户。后台任务（巩固 Worker 等）只拿到 sessionId，
     * 需要据此反查用户，才能把该用户配置的模型绑定到当前线程。
     */
    public Optional<Long> findUserIdBySession(String id) {
        if (Objects.isNull(id) || id.isBlank()) {
            return Optional.empty();
        }
        return conversationRepo.findById(id)
                .filter(c -> !c.isDeleted())
                .map(ChatConversation::getUserId);
    }

    /** Convert entity to model */
    private Conversation toModel(ChatConversation entity) {
        Conversation conv = new Conversation();
        conv.id = entity.getId();
        conv.title = entity.getTitle();
        conv.createdAt = entity.getCreatedAt();
        conv.updatedAt = entity.getUpdatedAt();
        conv.workspaceRoot = entity.getWorkspaceRoot();
        conv.messages = messageRepo.findByConversationIdOrderByTimestampAsc(entity.getId())
                .stream().map(m -> {
            Message msg = new Message();
            msg.role = m.getRole();
            msg.content = m.getContent();
            msg.timestamp = m.getTimestamp();
            if (Objects.nonNull(m.getImages())) {
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
        /** 会话绑定的工作目录；null = 跟随默认工作区。 */
        public String workspaceRoot;
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
