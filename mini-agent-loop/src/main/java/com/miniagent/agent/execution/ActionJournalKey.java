package com.miniagent.agent.execution;

import java.util.Objects;

/** 幂等唯一键：(sessionId, planVersion, nodeId, idempotencyKey)。 */
public record ActionJournalKey(String sessionId, String planVersion, String nodeId,
                               String idempotencyKey) {
    public ActionJournalKey {
        sessionId = normalized(sessionId, "anonymous");
        planVersion = normalized(planVersion, "unplanned-v1");
        nodeId = normalized(nodeId, "unknown-node");
        idempotencyKey = normalized(idempotencyKey, "none");
    }

    private static String normalized(String value, String fallback) {
        String normalized = Objects.requireNonNullElse(value, "").trim();
        return normalized.isEmpty() ? fallback : normalized;
    }
}
