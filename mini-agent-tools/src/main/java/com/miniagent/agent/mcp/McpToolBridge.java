package com.miniagent.agent.mcp;

import org.springframework.beans.factory.annotation.Autowired;

import com.miniagent.agent.tool.Tool;
import com.miniagent.agent.tool.ToolConcurrencyPolicy;
import com.miniagent.agent.tool.ToolErrorCode;
import com.miniagent.agent.tool.ToolRegistry;
import com.miniagent.agent.tool.ToolResult;
import com.miniagent.common.MessageConstants;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
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
    private final McpReconnectBudget reconnectBudget = new McpReconnectBudget();
    private final java.util.Set<String> reconnectExhaustionLogged = ConcurrentHashMap.newKeySet();

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

    /**
     * Recover a dead stdio child only within a bounded per-server budget. Exhaustion leaves
     * that server disconnected until an operator explicitly refreshes it or the window expires.
     */
    @Scheduled(fixedDelayString = "${agent.mcp.health-check-interval-ms:30000}")
    public synchronized void recoverUnhealthyServers() {
        if (!properties.isEnabled() || !properties.isAutoReconnectEnabled()) return;
        long now = System.currentTimeMillis();
        long windowMillis = Math.max(60, Math.min(86_400,
                properties.getReconnectWindowSeconds())) * 1_000L;
        int maxAttempts = Math.max(1, Math.min(10, properties.getMaxReconnectAttempts()));
        for (McpProperties.Server server : properties.getServers()) {
            if (StringUtils.isBlank(server.getId()) || server.getAllowedTools().isEmpty()) continue;
            McpStdioClient client = clients.get(server.getId());
            if (client != null && client.isHealthy()) continue;
            if (!reconnectBudget.allow(server.getId(), now, windowMillis, maxAttempts)) {
                if (client != null) {
                    clients.remove(server.getId(), client);
                    client.close();
                    unregisterServerTools(server.getId());
                }
                if (reconnectExhaustionLogged.add(server.getId())) {
                    log.error("MCP[{}] 自动重连预算耗尽，保持断开；需管理员 refresh 或等待预算窗口到期",
                            server.getId());
                }
                continue;
            }
            reconnectExhaustionLogged.remove(server.getId());
            if (client != null) {
                clients.remove(server.getId(), client);
                client.close();
                unregisterServerTools(server.getId());
            }
            try {
                connectServer(server, true);
                McpStdioClient recovered = clients.get(server.getId());
                if (recovered == null || !recovered.isHealthy()) {
                    throw new IllegalStateException("MCP reconnect did not establish a healthy process");
                }
                log.info("MCP[{}] 自动重连成功（窗口内尝试 {}/{}）", server.getId(),
                        reconnectBudget.attemptsInWindow(server.getId(), now, windowMillis), maxAttempts);
            } catch (Exception failure) {
                log.warn("MCP[{}] 自动重连失败（窗口内尝试 {}/{}）；下次检查前等待",
                        server.getId(), reconnectBudget.attemptsInWindow(server.getId(), now, windowMillis),
                        maxAttempts, failure);
            }
        }
    }

    /** 刷新全部已连接服务器的 tools/list */
    public synchronized Map<String, Object> refreshAll() {
        Map<String, Object> report = new LinkedHashMap<>();
        List<String> ok = new ArrayList<>();
        List<String> fail = new ArrayList<>();
        if (!properties.isEnabled()) {
            report.put("success", false);
            report.put("message", MessageConstants.MCP_NOT_ENABLED);
            return report;
        }
        for (McpProperties.Server server : properties.getServers()) {
            try {
                Map<String, Object> refreshed = refreshServer(server.getId());
                if (Boolean.TRUE.equals(refreshed.get("success"))) {
                    ok.add(server.getId());
                } else {
                    fail.add(server.getId() + ": " + refreshed.getOrDefault("message", "refresh rejected"));
                }
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

        if (cfg.getAllowedTools().isEmpty()) {
            unregisterServerTools(serverId);
            McpStdioClient disabled = clients.remove(serverId);
            if (disabled != null) disabled.close();
            log.warn("MCP[{}] 未配置 allowed-tools，服务器保持禁用且不会启动子进程", serverId);
            return Map.of("success", false, "serverId", serverId,
                    "message", "服务器未配置非空 allowed-tools 白名单，未启动连接");
        }

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
        if (server.getAllowedTools().isEmpty()) {
            McpStdioClient disabled = clients.remove(server.getId());
            if (disabled != null) disabled.close();
            unregisterServerTools(server.getId());
            log.warn("MCP[{}] 未配置 allowed-tools，服务器保持禁用且不会启动子进程", server.getId());
            return;
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
        try {
            registerToolsFromClient(server.getId(), client);
        } catch (Exception e) {
            unregisterServerTools(server.getId());
            clients.remove(server.getId(), client);
            client.close();
            throw e;
        }
    }

    private void registerToolsFromClient(String serverId, McpStdioClient client) throws Exception {
        List<McpStdioClient.McpToolDef> tools = client.listTools();
        McpProperties.Server cfg = properties.getServers().stream()
                .filter(server -> serverId.equals(server.getId())).findFirst()
                .orElseThrow(() -> new IllegalStateException("MCP server configuration disappeared: " + serverId));
        requireExactToolAllowlist(cfg, tools);
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
                    .sideEffect(com.miniagent.agent.tool.ToolSideEffect.EXTERNAL_WRITE)
                    .idempotent(false)
                    .cancellable(false)
                    .executionProfile(ToolConcurrencyPolicy.mcpExternalWriteProfile(
                            serverId, cfg.getRequestTimeoutSeconds()))
                    .resultHandler(args -> invoke(serverId, toolName, args))
                    .build());
            names.add(regName);
            registeredNames.add(regName);
            log.info("注册 MCP 工具: {}", regName);
        }
        namesByServer.put(serverId, names);
    }

    static void requireExactToolAllowlist(McpProperties.Server server,
                                          List<McpStdioClient.McpToolDef> remoteTools) {
        java.util.Set<String> allowed = new java.util.LinkedHashSet<>(server.getAllowedTools());
        java.util.Set<String> advertised = remoteTools.stream().map(McpStdioClient.McpToolDef::name)
                .filter(Objects::nonNull).collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        java.util.Set<String> sanitizedNames = remoteTools.stream().map(McpStdioClient.McpToolDef::name)
                .filter(Objects::nonNull).map(McpToolBridge::sanitize)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        if (allowed.isEmpty()) {
            throw new IllegalStateException("MCP[" + server.getId() + "] 缺少 allowed-tools，拒绝注册远端工具");
        }
        if (advertised.size() != remoteTools.size()) {
            throw new IllegalStateException("MCP[" + server.getId() + "] tools/list 包含重复工具名，拒绝注册");
        }
        if (sanitizedNames.size() != advertised.size()) {
            throw new IllegalStateException("MCP[" + server.getId() + "] 工具名在本地注册名中冲突，拒绝注册");
        }
        java.util.Set<String> unapproved = new java.util.LinkedHashSet<>(advertised);
        unapproved.removeAll(allowed);
        java.util.Set<String> unavailable = new java.util.LinkedHashSet<>(allowed);
        unavailable.removeAll(advertised);
        if (!unapproved.isEmpty() || !unavailable.isEmpty()) {
            throw new IllegalStateException("MCP[" + server.getId() + "] tools/list 与 allowed-tools 不一致；"
                    + "未批准的远端工具=" + unapproved + "，白名单中未提供的工具=" + unavailable
                    + "；拒绝注册该服务器全部工具");
        }
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

    private ToolResult invoke(String serverId, String toolName, String argsJson) {
        McpStdioClient client = clients.get(serverId);
        if (Objects.isNull(client)) {
            return ToolResult.failure(ToolErrorCode.DEPENDENCY_UNAVAILABLE,
                    String.format(MessageConstants.MCP_SERVER_NOT_CONNECTED, serverId), true);
        }
        try {
            String raw = client.callTool(toolName, argsJson);
            return raw == null || raw.isBlank()
                    ? ToolResult.unknown("MCP 返回空结果，无法确认操作是否已生效；请先核验实际状态，禁止直接重试", null)
                    : ToolResult.fromLegacy(raw);
        } catch (Exception e) {
            log.warn("MCP 调用失败 {}.{}: {}", serverId, toolName, e.getMessage());
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            // MCP tools are conservatively registered as external writes. A
            // transport exception cannot establish whether tools/call committed.
            return ToolResult.unknown(String.format(MessageConstants.MCP_CALL_FAILED, e.getMessage())
                    + "；操作可能已在 MCP 服务端生效，请先核验实际状态，禁止直接重试", null);
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
