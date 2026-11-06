package com.miniagent.agent.memory.manager;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/** Procedure 实现选择。remote 访问使用 Account 签发的用户凭证。 */
@ConfigurationProperties(prefix = "agent.memory.service")
public record ProcedureSettlementModeProperties(
        String mode,
        String baseUrl,
        String internalKey,
        String routerKey,
        int connectTimeoutMs,
        int readTimeoutMs,
        int contextTimeoutMs,
        int fallbackTtlHours,
        String envVersion
) {
    public static final String MODE_LOCAL = "local";
    public static final String MODE_REMOTE = "remote";
    private static final int DEFAULT_CONNECT_MS = 3000;
    private static final int DEFAULT_READ_MS = 10000;
    private static final int DEFAULT_CONTEXT_MS = 300;
    private static final int DEFAULT_FALLBACK_HOURS = 6;

    public ProcedureSettlementModeProperties(String mode, String baseUrl, String internalKey,
                                             int connectTimeoutMs, int readTimeoutMs) {
        this(mode, baseUrl, internalKey, "", connectTimeoutMs, readTimeoutMs,
                DEFAULT_CONTEXT_MS, DEFAULT_FALLBACK_HOURS, "");
    }

    public ProcedureSettlementModeProperties(String mode, String baseUrl, String internalKey,
                                             String routerKey, int connectTimeoutMs, int readTimeoutMs) {
        this(mode, baseUrl, internalKey, routerKey, connectTimeoutMs, readTimeoutMs,
                DEFAULT_CONTEXT_MS, DEFAULT_FALLBACK_HOURS, "");
    }

    @ConstructorBinding
    public ProcedureSettlementModeProperties {
        mode = mode == null || mode.isBlank() ? MODE_LOCAL : mode.trim().toLowerCase();
        baseUrl = normalize(baseUrl);
        internalKey = internalKey == null ? "" : internalKey.trim();
        routerKey = routerKey == null ? "" : routerKey.trim();
        connectTimeoutMs = connectTimeoutMs <= 0 ? DEFAULT_CONNECT_MS : connectTimeoutMs;
        readTimeoutMs = readTimeoutMs <= 0 ? DEFAULT_READ_MS : readTimeoutMs;
        contextTimeoutMs = contextTimeoutMs <= 0 ? DEFAULT_CONTEXT_MS : contextTimeoutMs;
        fallbackTtlHours = fallbackTtlHours <= 0 ? DEFAULT_FALLBACK_HOURS : fallbackTtlHours;
        envVersion = envVersion == null ? "" : envVersion.trim();
    }

    public boolean remote() {
        return MODE_REMOTE.equals(mode);
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
