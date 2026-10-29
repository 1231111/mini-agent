package com.miniagent.memory.service.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Profile;

/** 记忆服务内部调用认证配置。密钥为空时服务拒绝所有结算请求。 */
@ConfigurationProperties(prefix = "memory.service.security")
@Profile("memory-service")
public record MemoryServiceSecurityProperties(String internalKey) {
    public MemoryServiceSecurityProperties {
        internalKey = internalKey == null ? "" : internalKey.trim();
    }

    public boolean configured() {
        return !internalKey.isEmpty();
    }
}
