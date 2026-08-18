package com.miniagent.memory;

import com.miniagent.memory.model.AgentEvent;
import com.miniagent.memory.model.MemoryScope;
import com.miniagent.memory.model.MemoryType;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** 统一记忆作用域决策，写入端和检索端不再各自猜测。 */
public final class MemoryScopePolicy {
    private MemoryScopePolicy() {}

    public static MemoryScope resolve(AgentEvent event, MemoryType type) {
        String tenant = normalized(event == null ? null : event.getTenantId(), "default");
        Map<String, Object> payload = event == null || event.getPayload() == null
                ? Map.of() : event.getPayload();

        MemoryScope explicit = explicitScope(tenant, payload);
        if (explicit != null) return explicit;

        String userId = normalized(first(payload.get("userId"), payload.get("user_id")), "");
        String projectId = normalized(first(payload.get("projectId"), payload.get("project_id")), "");
        String sessionId = normalized(event == null ? null : event.getSessionId(), "global");

        return switch (type == null ? MemoryType.EPISODIC : type) {
            case USER -> !userId.isEmpty()
                    ? MemoryScope.ofUser(tenant, userId)
                    : MemoryScope.ofSession(tenant, sessionId);
            case PROJECT, PROCEDURAL -> !projectId.isEmpty()
                    ? MemoryScope.ofProject(tenant, projectId)
                    : fallbackUserOrSession(tenant, userId, sessionId);
            case ORGANIZATION -> new MemoryScope(tenant,
                    MemoryScope.ScopeType.ORGANIZATION,
                    normalized(Objects.toString(payload.get("organizationId"), ""), tenant));
            case WORKING, EPISODIC -> MemoryScope.ofSession(tenant, sessionId);
            case SEMANTIC -> {
                if (!projectId.isEmpty()) yield MemoryScope.ofProject(tenant, projectId);
                boolean stableUserFact = "user".equalsIgnoreCase(event == null ? "" : event.getActor())
                        || Boolean.TRUE.equals(payload.get("stable"));
                if (stableUserFact && !userId.isEmpty()) yield MemoryScope.ofUser(tenant, userId);
                yield MemoryScope.ofSession(tenant, sessionId);
            }
        };
    }

    private static MemoryScope explicitScope(String tenant, Map<String, Object> payload) {
        String type = Objects.toString(payload.get("scopeType"), "").trim();
        String id = Objects.toString(payload.get("scopeId"), "").trim();
        if (type.isEmpty() || id.isEmpty()) return null;
        try {
            return new MemoryScope(tenant,
                    MemoryScope.ScopeType.valueOf(type.toUpperCase(Locale.ROOT)), id);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static MemoryScope fallbackUserOrSession(String tenant, String userId, String sessionId) {
        return !userId.isEmpty() ? MemoryScope.ofUser(tenant, userId)
                : MemoryScope.ofSession(tenant, sessionId);
    }

    private static Object first(Object preferred, Object fallback) {
        return preferred == null || preferred.toString().isBlank() ? fallback : preferred;
    }

    private static String normalized(Object value, String fallback) {
        String text = Objects.toString(value, "").trim();
        return text.isEmpty() ? fallback : text;
    }
}
