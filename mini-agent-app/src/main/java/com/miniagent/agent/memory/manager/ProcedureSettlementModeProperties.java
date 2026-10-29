package com.miniagent.agent.memory.manager;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Procedure 结算实现选择。local 适用于桌面档，remote 适用于云端记忆服务。 */
@ConfigurationProperties(prefix = "agent.memory.service")
public record ProcedureSettlementModeProperties(
        String mode,
        String baseUrl,
        String internalKey,
        int connectTimeoutMs,
        int readTimeoutMs
) {
    public ProcedureSettlementModeProperties {
        mode = mode == null || mode.isBlank() ? "local" : mode.trim().toLowerCase();
        baseUrl = normalize(baseUrl);
        internalKey = internalKey == null ? "" : internalKey.trim();
        connectTimeoutMs = connectTimeoutMs <= 0 ? 3000 : connectTimeoutMs;
        readTimeoutMs = readTimeoutMs <= 0 ? 10000 : readTimeoutMs;
    }

    public boolean remote() {
        return "remote".equals(mode);
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
