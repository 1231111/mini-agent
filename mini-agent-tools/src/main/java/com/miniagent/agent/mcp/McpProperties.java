package com.miniagent.agent.mcp;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * agent.mcp.* 配置。
 */
@ConfigurationProperties(prefix = "agent.mcp")
public class McpProperties {

    private boolean enabled = false;
    /** Restart unhealthy stdio servers with a per-server rolling budget. */
    private boolean autoReconnectEnabled = true;
    private int maxReconnectAttempts = 3;
    private int reconnectWindowSeconds = 900;
    private List<Server> servers = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isAutoReconnectEnabled() { return autoReconnectEnabled; }
    public void setAutoReconnectEnabled(boolean autoReconnectEnabled) {
        this.autoReconnectEnabled = autoReconnectEnabled;
    }
    public int getMaxReconnectAttempts() { return maxReconnectAttempts; }
    public void setMaxReconnectAttempts(int maxReconnectAttempts) {
        this.maxReconnectAttempts = maxReconnectAttempts;
    }
    public int getReconnectWindowSeconds() { return reconnectWindowSeconds; }
    public void setReconnectWindowSeconds(int reconnectWindowSeconds) {
        this.reconnectWindowSeconds = reconnectWindowSeconds;
    }

    public List<Server> getServers() {
        return servers;
    }

    public void setServers(List<Server> servers) {
        this.servers = Optional.ofNullable(servers).orElse(new ArrayList<>());
    }

    public static class Server {
        private String id;
        private String command;
        /** Exact absolute executable path approved for this server. Empty disables process launch. */
        private String allowedExecutable;
        private List<String> args = new ArrayList<>();
        private Map<String, String> env = new HashMap<>();
        /** Exact remote tool names approved for this server; an empty list keeps the server disabled. */
        private List<String> allowedTools = new ArrayList<>();
        /** stdio | http（http 预留） */
        private String transport = "stdio";
        private String url;
        /** Maximum JSON-RPC frame accepted from this process. */
        private int maxFrameBytes = 8 * 1024 * 1024;
        /** Upper bound for a tools/list or tools/call response wait. */
        private int requestTimeoutSeconds = 120;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getCommand() { return command; }
        public void setCommand(String command) { this.command = command; }
        public String getAllowedExecutable() { return allowedExecutable; }
        public void setAllowedExecutable(String allowedExecutable) { this.allowedExecutable = allowedExecutable; }
        public List<String> getArgs() { return args; }
        public void setArgs(List<String> args) { this.args = Optional.ofNullable(args).orElse(new ArrayList<>()); }
        public Map<String, String> getEnv() { return env; }
        public void setEnv(Map<String, String> env) { this.env = Optional.ofNullable(env).orElse(new HashMap<>()); }
        public List<String> getAllowedTools() { return allowedTools; }
        public void setAllowedTools(List<String> allowedTools) {
            this.allowedTools = Optional.ofNullable(allowedTools).orElse(new ArrayList<>()).stream()
                    .filter(java.util.Objects::nonNull).map(String::trim)
                    .filter(value -> !value.isEmpty()).distinct().toList();
        }
        public String getTransport() { return transport; }
        public void setTransport(String transport) { this.transport = transport; }
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public int getMaxFrameBytes() { return maxFrameBytes; }
        public void setMaxFrameBytes(int maxFrameBytes) { this.maxFrameBytes = maxFrameBytes; }
        public int getRequestTimeoutSeconds() { return requestTimeoutSeconds; }
        public void setRequestTimeoutSeconds(int requestTimeoutSeconds) {
            this.requestTimeoutSeconds = requestTimeoutSeconds;
        }
    }
}
