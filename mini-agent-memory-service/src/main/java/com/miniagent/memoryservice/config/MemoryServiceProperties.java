package com.miniagent.memoryservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Map;

/**
 * 部署角色与服务令牌签发方。
 *
 * @param role    api（只服务 HTTP）/ worker（升级作业和离线回放）/ all（HTTP 与作业一起跑）
 * @param issuers 签发方 → 公钥列表（轮换期并存）+ 允许签发的角色
 */
@ConfigurationProperties(prefix = "memory.service")
public record MemoryServiceProperties(String role, Map<String, Issuer> issuers,
                                      String accountBaseUrl, String accountInternalKey,
                                      String memoryTokenKey, int accountConnectTimeoutMs,
                                      int accountReadTimeoutMs) {
    public static final String ROLE_API = "api";
    public static final String ROLE_WORKER = "worker";
    public static final String ROLE_ALL = "all";

    public MemoryServiceProperties {
        role = role == null || role.isBlank() ? ROLE_API : role.trim().toLowerCase();
        issuers = issuers == null ? Map.of() : Map.copyOf(issuers);
        accountBaseUrl = accountBaseUrl == null ? "" : accountBaseUrl.trim();
        accountInternalKey = accountInternalKey == null ? "" : accountInternalKey.trim();
        memoryTokenKey = memoryTokenKey == null ? "" : memoryTokenKey.trim();
        accountConnectTimeoutMs = accountConnectTimeoutMs <= 0 ? 1500 : accountConnectTimeoutMs;
        accountReadTimeoutMs = accountReadTimeoutMs <= 0 ? 2500 : accountReadTimeoutMs;
    }

    public boolean runsWorker() {
        return ROLE_WORKER.equals(role) || ROLE_ALL.equals(role);
    }

    public record Issuer(List<String> publicKeys, List<String> roles) {
        public Issuer {
            publicKeys = publicKeys == null ? List.of()
                    : publicKeys.stream().filter(k -> k != null && !k.isBlank()).toList();
            roles = roles == null ? List.of() : List.copyOf(roles);
        }
    }
}
