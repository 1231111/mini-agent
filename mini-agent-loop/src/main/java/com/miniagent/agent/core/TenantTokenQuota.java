package com.miniagent.agent.core;

/** Application-provided tenant token quota boundary used by the execution loop. */
public interface TenantTokenQuota {

    record Decision(boolean allowed, long usedTokens, long limitTokens) {
        public static Decision unlimited(long used) { return new Decision(true, used, 0); }
    }

    Decision check(Long tenantId);

    Decision consume(Long tenantId, long tokens);
}
