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
    private List<Server> servers = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
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
        private List<String> args = new ArrayList<>();
        private Map<String, String> env = new HashMap<>();
        /** stdio | http（http 预留） */
        private String transport = "stdio";
        private String url;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getCommand() { return command; }
        public void setCommand(String command) { this.command = command; }
        public List<String> getArgs() { return args; }
        public void setArgs(List<String> args) { this.args = Optional.ofNullable(args).orElse(new ArrayList<>()); }
        public Map<String, String> getEnv() { return env; }
        public void setEnv(Map<String, String> env) { this.env = Optional.ofNullable(env).orElse(new HashMap<>()); }
        public String getTransport() { return transport; }
        public void setTransport(String transport) { this.transport = transport; }
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
    }
}
