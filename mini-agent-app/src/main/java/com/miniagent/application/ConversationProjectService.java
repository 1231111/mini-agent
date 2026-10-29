package com.miniagent.application;

import com.miniagent.agent.memory.service.AgentProjectAuthorizationService;
import com.miniagent.config.service.DatabaseConversationStore;
import com.miniagent.common.context.OwnerContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConversationProjectService {
    private final DatabaseConversationStore conversations;
    private final AgentProjectAuthorizationService authorization;

    public ConversationProjectService(DatabaseConversationStore conversations,
                                      AgentProjectAuthorizationService authorization) {
        this.conversations = conversations;
        this.authorization = authorization;
    }

    @Transactional
    public void bind(Long userId, String sessionId, String projectId) {
        String tenant = OwnerContext.effectiveTenantId();
        authorization.requireMember(projectId, userId, tenant);
        if (!conversations.setProjectId(userId, sessionId, projectId)) {
            throw new IllegalArgumentException("Conversation is not owned by current user");
        }
    }

    @Transactional(readOnly = true)
    public String resolveAuthorized(Long userId, String tenantId, String sessionId) {
        String projectId = conversations.findProjectIdForUser(userId, sessionId).orElse(null);
        if (projectId == null) return null;
        authorization.requireMember(projectId, userId, tenantId);
        return projectId;
    }

    @Transactional
    public void unbind(Long userId, String sessionId) {
        if (!conversations.setProjectId(userId, sessionId, null)) {
            throw new IllegalArgumentException("Conversation is not owned by current user");
        }
    }
}
