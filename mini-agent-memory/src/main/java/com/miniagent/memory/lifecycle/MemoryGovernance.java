package com.miniagent.memory.lifecycle;

import com.miniagent.memory.model.MemoryScope;

/**
 * 记忆治理：权限/隔离/审计。
 */
public interface MemoryGovernance {

    /**
     * 检查是否有权在指定 scope 下执行操作。
     */
    boolean checkAccess(MemoryScope scope, String operation);

    /**
     * 验证 scope 隔离：确保不会跨租户访问。
     */
    boolean validateIsolation(MemoryScope requestScope, MemoryScope dataScope);
}
