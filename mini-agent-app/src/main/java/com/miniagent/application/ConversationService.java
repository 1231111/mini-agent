package com.miniagent.application;

import com.miniagent.agent.context.SessionHistoryVectorStore;
import com.miniagent.common.event.SessionEvictedEvent;
import com.miniagent.config.service.DatabaseConversationStore;
import com.miniagent.config.service.DatabaseConversationStore.Conversation;
import com.miniagent.config.service.DatabaseConversationStore.ConversationSummary;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * 会话 CRUD 管理（供 Controller 调用）。
 * 从 AgentChatApplicationService 提取而来。
 */
@Service
@Slf4j
public class ConversationService {

    @Autowired
    private DatabaseConversationStore conversationStore;
    @Autowired(required = false)
    private SessionHistoryVectorStore sessionHistoryVectorStore;
    /**
     * 会话被清空/删除时广播 {@link SessionEvictedEvent}，让各处的会话级进程内状态一起回收。
     *
     * <p>为什么放在这一层：这里是"会话被永久丢弃"的唯一收口 —— 以前清理动作只写了
     * "删库 + 删向量"，而 {@code ChatMemoryConfig.memories}、压缩状态、任务作用域、
     * 执行租约、token 统计这些进程内副本没人通知。<b>消息窗口不清还有更直接的后果</b>：
     * {@code chatMemoryProvider} 取到已缓存的实例就直接返回，清空会话后接着聊，
     * 模型仍能看到被"清空"的历史，直到重启进程。</p>
     */
    @Autowired
    private ApplicationEventPublisher eventPublisher;

    public void resetConversation(Long userId, String sessionId) {
        if (StringUtils.isNotBlank(sessionId)) {
            String sid = sessionId.trim();
            if (conversationStore.deleteForUser(userId, sid)) {
                deleteSessionHistoryVectors(sid);
                evictSessionCaches(sid);
            }
        }
    }

    public List<ConversationSummary> listConversations(Long userId) {
        return conversationStore.list(userId);
    }

    public Conversation getConversation(String sessionId) {
        return conversationStore.get(sessionId);
    }

    public Conversation getConversationForUser(Long userId, String sessionId) {
        return conversationStore.getForUser(userId, sessionId);
    }

    public boolean deleteConversation(String sessionId) {
        boolean ok = conversationStore.delete(sessionId);
        if (ok) {
            deleteSessionHistoryVectors(sessionId);
            evictSessionCaches(sessionId);
        }
        return ok;
    }

    public boolean deleteConversationForUser(Long userId, String sessionId) {
        boolean ok = conversationStore.deleteForUser(userId, sessionId);
        if (ok) {
            deleteSessionHistoryVectors(sessionId);
            evictSessionCaches(sessionId);
        }
        return ok;
    }

    public boolean renameConversation(String sessionId, String newTitle) {
        return conversationStore.rename(sessionId, newTitle);
    }

    private void deleteSessionHistoryVectors(String sessionId) {
        if (sessionHistoryVectorStore != null && StringUtils.isNotBlank(sessionId)) {
            try {
                sessionHistoryVectorStore.deleteSession(sessionId.trim());
            } catch (Exception e) {
                log.warn("删除会话历史向量失败 sessionId={}: {}", sessionId, e.getMessage());
            }
        }
    }

    /**
     * 广播「会话已删除」，回收各处的会话级进程内状态。
     *
     * <p>失败只记日志、不打断删除本身：库已经删掉了，不能因为某个缓存没清干净就把
     * 一次成功的删除回滚成失败。</p>
     */
    private void evictSessionCaches(String sessionId) {
        if (StringUtils.isBlank(sessionId)) {
            return;
        }
        try {
            eventPublisher.publishEvent(SessionEvictedEvent.deleted(sessionId.trim()));
        } catch (Exception e) {
            log.warn("回收会话进程内缓存失败 sessionId={}: {}", sessionId, e.getMessage());
        }
    }
}
