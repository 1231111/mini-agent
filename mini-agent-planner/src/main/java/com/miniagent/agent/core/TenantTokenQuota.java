package com.miniagent.agent.core;

/** Application-provided tenant token quota boundary used by the execution loop. */
public interface TenantTokenQuota {

    record Decision(boolean allowed, long usedTokens, long limitTokens) {
        public static Decision unlimited(long used) { return new Decision(true, used, 0); }
    }

    Decision check(Long tenantId, Long userId);

    Decision consume(Long tenantId, Long userId, long tokens);

    /** Consume exact provider usage and optionally persist its delivery identity atomically. */
    default Decision consume(Long tenantId, Long userId, long tokens, String eventId,
                             long inputTokens, long outputTokens) {
        return consume(tenantId, userId, tokens);
    }
}
