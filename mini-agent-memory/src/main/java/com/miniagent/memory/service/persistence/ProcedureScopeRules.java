package com.miniagent.memory.service.persistence;

/** 服务端 Procedure scope 格式与归属规则。 */
final class ProcedureScopeRules {
    static final String USER = "USER";
    static final String PROJECT = "PROJECT";

    private ProcedureScopeRules() {
    }

    static boolean projectionAccepted(String scopeType, String scopeId, Long userId) {
        if (USER.equalsIgnoreCase(scopeType)) {
            return scopeId != null && userId != null && scopeId.equals(String.valueOf(userId));
        }
        return PROJECT.equalsIgnoreCase(scopeType) && scopeId != null && !scopeId.isBlank();
    }
}
