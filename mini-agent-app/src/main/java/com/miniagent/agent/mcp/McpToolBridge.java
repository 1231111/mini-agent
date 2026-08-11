package com.miniagent.agent.mcp;

import org.springframework.beans.factory.annotation.Autowired;

import com.miniagent.agent.tool.Tool;
import com.miniagent.agent.tool.ToolRegistry;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Objects;
import org.apache.commons.lang3.StringUtils;

/**
 * 将 MCP 服务器 tools/list 动态注册进 {@link ToolRegistry}，命名 mcp__{serverId}__{tool}。
 * 支持 refresh 热更新（重新 tools/list 并替换注册）。
 */
@Slf4j
@Component
@EnableConfigurationProperties(McpProperties.class)
public class McpToolBridge {

    @Autowired
    private McpProperties properties;
    @Autowired
    private ToolRegistry toolRegistry;
    private final ConcurrentHashMap<String, McpStdioClient> clients = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<String>> namesByServer = new ConcurrentHashMap<>();
    private final List<String> registeredNames = new ArrayList<>();

    @EventListener(ApplicationReadyEvent.class)
    public void connectAll() {
        if (!properties.isEnabled()) {
            log.info("MCP 未启用（agent.mcp.enabled=false）");
            return;
        }
        if (Objects.isNull(properties.getServers()) || properties.getServers().isEmpty()) {
            log.info("MCP 已启用但未配置 servers");
            return;
        }
        for (McpProperties.Server server : properties.getServers()) {
            try {
                connectServer(server, false);
            } catch (Exception e) {
                log.warn("MCP 服务器 [{}] 连接失败: {}", server.getId(), e.getMessage());
            }
        }
        log.info("MCP 桥接完成，注册工具 {} 个", registeredNames.size());
    }

    /** 刷新全部已连接服务器的 tools/list */
    public synchronized Map<String, Object> refreshAll() {
        Map<String, Object> report = new LinkedHashMap<>();
        List<String> ok = new ArrayList<>();
        List<String> fail = new ArrayList<>();
        if (!properties.isEnabled()) {
            report.put("success", false);
            report.put("message", "MCP 未启用");
            return report;
        }
        for (McpProperties.Server server : properties.getServers()) {
            try {
                refreshServer(server.getId());
                ok.add(server.getId());
            } catch (Exception e) {
                fail.add(server.getId() + ": " + e.getMessage());
            }
        }
        report.put("success", fail.isEmpty());
        report.put("refreshed", ok);
        report.put("failed", fail);
        report.put("registeredTools", registeredToolNames());
        report.put("toolCount", registeredNames.size());
        return report;
    }

    /** 刷新单个服务器：优先复用进程 listTools；失败则重启连接 */
    public synchronized Map<String, Object> refreshServer(String serverId) throws Exception {
        if (StringUtils.isBlank(serverId)) {
            throw new IllegalArgumentException("serverId required");
        }
        McpProperties.Server cfg = properties.getServers().stream()
                .filter(s -> serverId.equals(s.getId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知 MCP 服务器: " + serverId));

        unregisterServerTools(serverId);
        McpStdioClient existing = clients.get(serverId);
        try {
            if (Objects.nonNull(existing)) {
                registerToolsFromClient(serverId, existing);
            } else {
                connectServer(cfg, false);
            }
        } catch (Exception e) {
            log.warn("MCP[{}] list 刷新失败，尝试重启进程: {}", serverId, e.getMessage());
            if (Objects.nonNull(existing)) {
                try { existing.close(); } catch (Exception ignored) {}
                clients.remove(serverId);
            }
            connectServer(cfg, true);
        }
        return Map.of(
                "success", true,
                "serverId", serverId,
                "tools", namesByServer.getOrDefault(serverId, List.of())
        );
    }

    private void connectServer(McpProperties.Server server, boolean force) throws Exception {
        if (StringUtils.isBlank(server.getId())) {
            throw new IllegalArgumentException("server.id 必填");
        }
        String transport = Objects.isNull(server.getTransport()) ? "stdio" : server.getTransport().toLowerCase();
        if (!"stdio".equals(transport)) {
            log.warn("MCP[{}] transport={} 暂未实现，跳过（当前仅 stdio）", server.getId(), transport);
            return;
        }
        if (!force && clients.containsKey(server.getId())) {
            refreshServer(server.getId());
            return;
        }
        McpStdioClient client = McpStdioClient.start(server);
        clients.put(server.getId(), client);
        registerToolsFromClient(server.getId(), client);
    }

    private void registerToolsFromClient(String serverId, McpStdioClient client) throws Exception {
        List<McpStdioClient.McpToolDef> tools = client.listTools();
        List<String> names = new ArrayList<>();
        for (McpStdioClient.McpToolDef t : tools) {
            String regName = "mcp__" + sanitize(serverId) + "__" + sanitize(t.name());
            String desc = "[MCP:" + serverId + "] " + (Objects.isNull(t.description()) ? t.name() : t.description());
            Map<String, Object> params = Objects.isNull(t.parameters()) ? Map.of() : t.parameters();
            final String toolName = t.name();
            toolRegistry.register(Tool.builder()
                    .name(regName)
                    .description(desc)
                    .parameters(params)
                    .handler(args -> invoke(serverId, toolName, args))
                    .build());
            names.add(regName);
            registeredNames.add(regName);
            log.info("注册 MCP 工具: {}", regName);
        }
        namesByServer.put(serverId, names);
    }

    private void unregisterServerTools(String serverId) {
        List<String> names = namesByServer.remove(serverId);
        if (Objects.nonNull(names)) {
            for (String n : names) {
                toolRegistry.unregister(n);
                registeredNames.remove(n);
            }
        } else {
            String prefix = "mcp__" + sanitize(serverId) + "__";
            toolRegistry.unregisterByPrefix(prefix);
            registeredNames.removeIf(n -> n.startsWith(prefix));
        }
    }

    private String invoke(String serverId, String toolName, String argsJson) {
        McpStdioClient client = clients.get(serverId);
        if (Objects.isNull(client)) {
            return "{\"error\":\"MCP 服务器未连接: " + serverId + "\"}";
        }
        try {
            return client.callTool(toolName, argsJson);
        } catch (Exception e) {
            log.warn("MCP 调用失败 {}.{}: {}", serverId, toolName, e.getMessage());
            return "{\"error\":\"MCP 调用失败: " + e.getMessage().replace("\"", "'") + "\"}";
        }
    }

    private static String sanitize(String s) {
        return Objects.isNull(s) ? "x" : s.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    @PreDestroy
    public void shutdown() {
        for (String name : List.copyOf(registeredNames)) {
            toolRegistry.unregister(name);
        }
        registeredNames.clear();
        namesByServer.clear();
        clients.values().forEach(McpStdioClient::close);
        clients.clear();
    }

    public List<String> registeredToolNames() {
        return List.copyOf(registeredNames);
    }

    public boolean isEnabled() {
        return properties.isEnabled();
    }
}
