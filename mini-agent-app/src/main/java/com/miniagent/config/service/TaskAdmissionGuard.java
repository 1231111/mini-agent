package com.miniagent.config.service;

import com.miniagent.agent.browser.BrowserService;
import com.miniagent.agent.browser.BrowserSessionIdentity;
import com.miniagent.agent.execution.ToolExecutionGuards;
import com.miniagent.agent.tool.WorkspaceContext;
import com.miniagent.common.context.OwnerContext;
import com.miniagent.application.ChatStreamingService;
import com.miniagent.config.cloud.TokenEntitlementGate;
import com.miniagent.config.repository.UserRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** Prevents a new turn from overlapping unfinished local work from its previous run. */
@Component
public class TaskAdmissionGuard {
    public enum Decision { ALLOW, BUSY, UNAVAILABLE, QUOTA_EXCEEDED }

    private final ObjectProvider<ToolExecutionGuards> tools;
    private final ObjectProvider<BrowserService> browsers;
    private final ObjectProvider<ChatStreamingService> streams;
    private final UserRepository users;
    private final TokenEntitlementGate entitlements;

    public TaskAdmissionGuard(ObjectProvider<ToolExecutionGuards> tools,
                              ObjectProvider<BrowserService> browsers, UserRepository users,
                              ObjectProvider<ChatStreamingService> streams,
                              TokenEntitlementGate entitlements) {
        this.tools = tools;
        this.browsers = browsers;
        this.users = users;
        this.streams = streams;
        this.entitlements = entitlements;
    }

    public Decision check(Long userId, String sessionId) {
        if (userId == null || sessionId == null || sessionId.isBlank()) return Decision.UNAVAILABLE;
        try {
            if (OwnerContext.currentUserId() != null && !userId.equals(OwnerContext.currentUserId()))
                return Decision.UNAVAILABLE;
            // The old SSE worker releases its durable task lease before the
            // outer wrapper finishes its terminal event/markIdle. The local
            // reservation must block synchronous turns during that interval too.
            if (streams.getObject().hasLocalLifecycleReservation(sessionId)) return Decision.BUSY;
            if (tools.getObject().hasActiveHandlers(sessionId)) return Decision.BUSY;
            // Admission precedes the worker's owner/workspace binding. Query only
            // the tenant projection, never model or memory credentials.
            Long tenant = users.findLocalTenantIdById(userId).orElse(null);
            if (tenant == null) return Decision.UNAVAILABLE;
            String tenantId = String.valueOf(tenant);
            if (OwnerContext.currentTenantId() != null && !tenantId.equals(OwnerContext.currentTenantId()))
                return Decision.UNAVAILABLE;
            String previousSession = WorkspaceContext.getSessionOverride();
            try (var ignored = OwnerContext.bind(new OwnerContext(userId, tenantId))) {
                WorkspaceContext.setSessionOverride(sessionId);
                if (browsers.getObject().hasPendingOperations(BrowserSessionIdentity.resolve(sessionId)))
                    return Decision.BUSY;
                // 额度放在最后一步：前面几步是本机状态检查，比一次远程查询便宜，
                // 而且 BUSY 的情况下没必要去问额度。
                // 额度兜底：HTTP 入口已由 TokenQuotaFilter 在更前面拦住，这里覆盖非 HTTP 触发的
                // 任务（恢复、定时等）。两者共用同一份 5 分钟缓存，不会多打账号服务。
                return entitlements.rejectionReason(userId) != null
                        ? Decision.QUOTA_EXCEEDED : Decision.ALLOW;
            } finally {
                // Root/task/operation group are not changed by this identity lookup.
                WorkspaceContext.setSessionOverride(previousSession);
            }
        } catch (RuntimeException unavailable) {
            // Missing identity/dependency or failed status reads cannot authorize
            // a paid model call or a new run over an unverified old operation.
            return Decision.UNAVAILABLE;
        }
    }
}
