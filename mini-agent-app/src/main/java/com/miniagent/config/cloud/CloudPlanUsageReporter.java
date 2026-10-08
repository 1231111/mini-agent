package com.miniagent.config.cloud;

import com.miniagent.agent.core.TokenUsageTracker;
import com.miniagent.config.entity.User;
import com.miniagent.config.repository.UserRepository;
import com.miniagent.config.service.DatabaseConversationStore;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * 每次模型调用结束后，把输入 token、输出 token 和 1 次 LLM 请求报到云端。
 * 上报走异步：账号服务读超时是 10 秒，不能卡在模型循环里。
 */
@Component
public class CloudPlanUsageReporter {

    private static final Logger log = LoggerFactory.getLogger(CloudPlanUsageReporter.class);

    @Autowired
    private CloudMembershipClient membership;
    @Autowired
    private DatabaseConversationStore conversations;
    @Autowired
    private UserRepository users;

    @PostConstruct
    void bind() {
        TokenUsageTracker.setListener(this::report);
    }

    @PreDestroy
    void unbind() {
        TokenUsageTracker.setListener(null);
    }

    private void report(String sessionId, long inputTokens, long outputTokens) {
        if (!membership.available()) {
            return;
        }
        CompletableFuture.runAsync(() -> send(sessionId, inputTokens, outputTokens));
    }

    private void send(String sessionId, long inputTokens, long outputTokens) {
        try {
            Long localUserId = conversations.findUserIdBySession(sessionId).orElse(null);
            if (localUserId == null) {
                return;
            }
            User user = users.findById(localUserId).orElse(null);
            if (user == null || user.getExternalId() == null || user.getExternalId().isBlank()) {
                return;
            }
            long cloudUserId = Long.parseLong(user.getExternalId().trim());
            membership.reportUsage(cloudUserId, inputTokens, outputTokens, 1);
        } catch (RuntimeException e) {
            log.warn("上报云端用量失败 session={}: {}", sessionId, e.getMessage());
        }
    }
}
