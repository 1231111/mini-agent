package com.miniagent.memory.model;

/**
 * 记忆作用域，用于多租户隔离。
 * scopeType + scopeId 唯一确定一个作用域。
 *
 * 层级关系：Tenant → Organization → User → Project → Agent → Session
 */
public record MemoryScope(
    String tenantId,
    ScopeType scopeType,
    String scopeId
) {
    public enum ScopeType {
        TENANT, ORGANIZATION, USER, PROJECT, AGENT, SESSION
    }

    public static MemoryScope ofUser(String tenantId, String userId) {
        return new MemoryScope(tenantId, ScopeType.USER, userId);
    }

    public static MemoryScope ofProject(String tenantId, String projectId) {
        return new MemoryScope(tenantId, ScopeType.PROJECT, projectId);
    }

    public static MemoryScope ofSession(String tenantId, String sessionId) {
        return new MemoryScope(tenantId, ScopeType.SESSION, sessionId);
    }

    public static MemoryScope ofTenant(String tenantId) {
        return new MemoryScope(tenantId, ScopeType.TENANT, tenantId);
    }
}
