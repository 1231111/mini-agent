package com.miniagent.memory.port;

import java.util.Objects;

/**
 * 由调用方认证并传入记忆服务的可信归属上下文。
 *
 * <p>该类型是跨进程契约，不接受 HTTP 请求体中的任意客户端身份字段作为替代。
 * 记忆服务收到请求后仍必须验证调用凭证与 tenant/user/project 的对应关系。</p>
 */
public record MemoryPrincipal(String tenantId, Long userId, String projectId, String sessionId) {
    public MemoryPrincipal {
        tenantId = requireText(tenantId, "tenantId");
        userId = Objects.requireNonNull(userId, "userId");
        projectId = normalize(projectId);
        sessionId = normalize(sessionId);
    }

    public boolean hasProject() {
        return projectId != null;
    }

    public boolean hasSession() {
        return sessionId != null;
    }

    private static String requireText(String value, String field) {
        String normalized = normalize(value);
        if (normalized == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
