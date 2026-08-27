package com.miniagent.config.security;

import com.miniagent.config.repository.ChatConversationRepository;
import com.miniagent.config.repository.ChatTaskRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

/** Central ownership check for every session-scoped API. */
@Service
public class SessionAuthorizationService {
    private final ChatConversationRepository conversations;
    private final ChatTaskRepository tasks;

    public SessionAuthorizationService(ChatConversationRepository conversations,
                                       ChatTaskRepository tasks) {
        this.conversations = conversations;
        this.tasks = tasks;
    }

    public boolean owns(Long userId, String sessionId) {
        if (userId == null || sessionId == null || sessionId.isBlank()) {
            return false;
        }
        return conversations.existsByIdAndUserId(sessionId, userId)
                || tasks.existsByUserIdAndSessionIdAndDeletedFalse(userId, sessionId);
    }

    public void requireOwner(Long userId, String sessionId) {
        if (!owns(userId, sessionId)) {
            throw new AccessDeniedException("Session is not owned by user");
        }
    }
}
