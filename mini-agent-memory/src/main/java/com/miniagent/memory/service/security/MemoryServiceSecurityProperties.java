package com.miniagent.memory.service.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.context.annotation.Profile;

/** 记忆服务内部调用认证配置。密钥为空时服务拒绝所有结算请求。 */
@ConfigurationProperties(prefix = "memory.service.security")
@Profile("memory-service")
public record MemoryServiceSecurityProperties(String internalKey, String runnerKey, String routerKey,
                                               long attestationMaxAgeSeconds) {
    @ConstructorBinding
    public MemoryServiceSecurityProperties {
        internalKey = normalize(internalKey);
        runnerKey = normalize(runnerKey);
        routerKey = normalize(routerKey);
        if (attestationMaxAgeSeconds < 1 || attestationMaxAgeSeconds > 86_400) {
            throw new IllegalArgumentException("attestationMaxAgeSeconds must be between 1 and 86400");
        }
    }

    public MemoryServiceSecurityProperties(String internalKey) {
        this(internalKey, "", "", 300);
    }

    public boolean configured() {
        return !internalKey.isEmpty();
    }

    public boolean runnerConfigured() {
        return !runnerKey.isEmpty();
    }

    public boolean routerConfigured() {
        return !routerKey.isEmpty();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
