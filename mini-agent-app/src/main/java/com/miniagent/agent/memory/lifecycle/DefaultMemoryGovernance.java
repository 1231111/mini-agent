package com.miniagent.agent.memory.lifecycle;

import com.miniagent.memory.lifecycle.MemoryGovernance;
import com.miniagent.memory.model.MemoryScope;
import org.springframework.stereotype.Component;

/**
 * 默认记忆治理：scope 隔离校验。
 */
@Component
public class DefaultMemoryGovernance implements MemoryGovernance {

    @Override
    public boolean checkAccess(MemoryScope scope, String operation) {
        // 基本校验：scope 不能为空
        if (scope == null || scope.tenantId() == null) {
            return false;
        }
        // 读操作：所有 scope 都允许
        if ("read".equals(operation) || "search".equals(operation)) {
            return true;
        }
        // 写操作：需要有明确的 scopeId
        if ("write".equals(operation) || "delete".equals(operation)) {
            return scope.scopeId() != null && !scope.scopeId().isEmpty();
        }
        return true;
    }

    @Override
    public boolean validateIsolation(MemoryScope requestScope, MemoryScope dataScope) {
        if (requestScope == null || dataScope == null) return false;
        // 必须同一租户
        return requestScope.tenantId() != null
            && requestScope.tenantId().equals(dataScope.tenantId());
    }
}
