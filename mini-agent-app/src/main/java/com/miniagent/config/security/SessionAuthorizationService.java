package com.miniagent.config.security;

import com.miniagent.config.repository.AgentTaskRunRepository;
import com.miniagent.config.repository.ChatConversationRepository;
import com.miniagent.config.repository.ChatTaskRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

/** Central ownership check for every session-scoped API. */
@Service
public class SessionAuthorizationService {

    @Autowired
    private ChatConversationRepository conversations;
    @Autowired
    private ChatTaskRepository tasks;
    @Autowired
    private AgentTaskRunRepository runs;

    public boolean owns(Long userId, String sessionId) {
        if (userId == null || sessionId == null || sessionId.isBlank()) {
            return false;
        }
        // 会话行与 ChatTask 都要等本轮跑完才写，任务执行期间只有 AgentTaskRun 能证明归属；
        // 少了这一条，运行中的 /api/traces 一律返回空，轨迹页要等任务结束才有内容。
        return conversations.existsByIdAndUserIdAndDeletedFalse(sessionId, userId)
                || tasks.existsByUserIdAndSessionIdAndDeletedFalse(userId, sessionId)
                || runs.existsByUserIdAndSessionId(userId, sessionId);
    }

    public void requireOwner(Long userId, String sessionId) {
        if (!owns(userId, sessionId)) {
            throw new AccessDeniedException("Session is not owned by user");
        }
    }
}
