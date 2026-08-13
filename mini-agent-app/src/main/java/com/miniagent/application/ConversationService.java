package com.miniagent.application;

import com.miniagent.agent.context.SessionHistoryVectorStore;
import com.miniagent.config.service.DatabaseConversationStore;
import com.miniagent.config.service.DatabaseConversationStore.Conversation;
import com.miniagent.config.service.DatabaseConversationStore.ConversationSummary;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
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

    public void resetConversation(Long userId, String sessionId) {
        if (StringUtils.isNotBlank(sessionId)) {
            String sid = sessionId.trim();
            conversationStore.delete(sid);
            deleteSessionHistoryVectors(sid);
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
        if (ok) deleteSessionHistoryVectors(sessionId);
        return ok;
    }

    public boolean deleteConversationForUser(Long userId, String sessionId) {
        boolean ok = conversationStore.deleteForUser(userId, sessionId);
        if (ok) deleteSessionHistoryVectors(sessionId);
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
}
