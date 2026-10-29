package com.miniagent.memoryservice.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 记忆服务内部调用认证配置。密钥为空时服务拒绝所有结算请求。 */
@ConfigurationProperties(prefix = "memory.service.security")
public record MemoryServiceSecurityProperties(String internalKey) {
    public MemoryServiceSecurityProperties {
        internalKey = internalKey == null ? "" : internalKey.trim();
    }

    public boolean configured() {
        return !internalKey.isEmpty();
    }
}
