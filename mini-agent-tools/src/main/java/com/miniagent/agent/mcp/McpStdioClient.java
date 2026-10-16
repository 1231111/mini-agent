package com.miniagent.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.miniagent.agent.tool.ProcessEnv;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Objects;
import java.util.Optional;
import org.apache.commons.lang3.StringUtils;

/**
 * 轻量 MCP stdio JSON-RPC 客户端（Content-Length 帧 + 兼容 NDJSON）。
 */
@Slf4j
public class McpStdioClient implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int DEFAULT_MAX_FRAME_BYTES = 8 * 1024 * 1024;
    private static final int MAX_HEADER_BYTES = 16 * 1024;
    private static final int MAX_PENDING_REQUESTS = 256;

    private final String serverId;
    private final Process process;
    private final int maxFrameBytes;
    private final int requestTimeoutSeconds;
    private final AtomicLong nextId = new AtomicLong(1);
    private final ConcurrentHashMap<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private volatile boolean closed;

    public McpStdioClient(String serverId, Process process) {
        this(serverId, process, DEFAULT_MAX_FRAME_BYTES, 120);
    }

    public McpStdioClient(String serverId, Process process,
                          int maxFrameBytes, int requestTimeoutSeconds) {
        this.serverId = serverId;
        this.process = process;
        this.maxFrameBytes = Math.max(64 * 1024,
                Math.min(64 * 1024 * 1024, maxFrameBytes));
        this.requestTimeoutSeconds = Math.max(1, Math.min(600, requestTimeoutSeconds));
        Thread.ofVirtual().name("mcp-stdio-" + serverId).start(this::readLoop);
        Thread.ofVirtual().name("mcp-stderr-" + serverId).start(this::drainStderr);
    }

    public static McpStdioClient start(McpProperties.Server cfg) throws Exception {
        if (StringUtils.isBlank(cfg.getCommand())) {
            throw new IllegalArgumentException("MCP server " + cfg.getId() + " 缺少 command");
        }
        List<String> cmd = new ArrayList<>();
        cmd.add(cfg.getCommand());
        if (Objects.nonNull(cfg.getArgs())) {
            cmd.addAll(cfg.getArgs());
        }
        ProcessBuilder pb = new ProcessBuilder(cmd);
        // 环境沙箱：先收敛到白名单，再合并本 server 显式声明的 env。
        // 继承整份环境意味着任意 MCP 服务（其工具由服务方定义、模型可直接调用）
        // 都能读到 agent 的模型 key 与数据库口令；需要 token 的服务请在配置的 env 里显式声明。
        ProcessEnv.sanitize(pb);
        ProcessEnv.putDeclared(pb, cfg.getEnv());
        pb.redirectErrorStream(false);
        Process p = pb.start();
        McpStdioClient client = new McpStdioClient(cfg.getId(), p,
                cfg.getMaxFrameBytes(), cfg.getRequestTimeoutSeconds());
        try {
            client.initialize();
            return client;
        } catch (Exception e) {
            client.close();
            throw e;
        }
    }

    private void initialize() throws Exception {
        ObjectNode params = MAPPER.createObjectNode();
        params.put("protocolVersion", "2024-11-05");
        params.set("capabilities", MAPPER.createObjectNode());
        ObjectNode clientInfo = MAPPER.createObjectNode();
        clientInfo.put("name", "miniagent");
        clientInfo.put("version", "0.1");
        params.set("clientInfo", clientInfo);
        await(request("initialize", params), Math.min(30, requestTimeoutSeconds));
        notify("notifications/initialized", MAPPER.createObjectNode());
    }

    public List<McpToolDef> listTools() throws Exception {
        JsonNode result = await(request("tools/list", MAPPER.createObjectNode()),
                Math.min(30, requestTimeoutSeconds));
        List<McpToolDef> out = new ArrayList<>();
        JsonNode tools = Objects.isNull(result) ? null : result.get("tools");
        if (Objects.isNull(tools) || !tools.isArray()) {
            return out;
        }
        for (JsonNode t : tools) {
            String name = text(t, "name");
            if (StringUtils.isBlank(name)) {
                continue;
            }
            String desc = text(t, "description");
            Map<String, Object> params = schemaToParams(t.get("inputSchema"));
            out.add(new McpToolDef(name, Optional.ofNullable(desc).orElse(""), params));
        }
        return out;
    }

    public String callTool(String name, String argumentsJson) throws Exception {
        ObjectNode params = MAPPER.createObjectNode();
        params.put("name", name);
        JsonNode argsNode;
        try {
            argsNode = StringUtils.isBlank(argumentsJson)
                    ? MAPPER.createObjectNode()
                    : MAPPER.readTree(argumentsJson);
        } catch (Exception e) {
            argsNode = MAPPER.createObjectNode();
        }
        params.set("arguments", argsNode);
        JsonNode result = await(request("tools/call", params), requestTimeoutSeconds);
        return formatCallResult(result);
    }

    private JsonNode await(CompletableFuture<JsonNode> future, int timeoutSeconds)
            throws Exception {
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            // A timed-out request must leave the pending map immediately. A
            // dead or malicious server must not retain one future per call.
            pending.values().remove(future);
            future.cancel(true);
            throw e;
        }
    }

    private String formatCallResult(JsonNode result) throws Exception {
        if (Objects.isNull(result)) {
            return "{\"error\":\"MCP 空结果\"}";
        }
        boolean isError = result.path("isError").asBoolean(false);
        JsonNode content = result.get("content");
        StringBuilder sb = new StringBuilder();
        if (Objects.nonNull(content) && content.isArray()) {
            for (JsonNode c : content) {
                String type = text(c, "type");
                if ("text".equals(type)) {
                    if (!sb.isEmpty()) {
                        sb.append('\n');
                    }
                    sb.append(c.path("text").asText(""));
                } else {
                    if (!sb.isEmpty()) {
                        sb.append('\n');
                    }
                    sb.append(MAPPER.writeValueAsString(c));
                }
            }
        }
        if (sb.isEmpty() && result.has("structuredContent")) {
            sb.append(MAPPER.writeValueAsString(result.get("structuredContent")));
        }
        if (isError) {
            return MAPPER.writeValueAsString(Map.of("error", sb.toString()));
        }
        return sb.isEmpty() ? MAPPER.writeValueAsString(result) : sb.toString();
    }

    private CompletableFuture<JsonNode> request(String method, JsonNode params) throws Exception {
        if (closed) {
            throw new IllegalStateException("MCP 连接已关闭");
        }
        if (pending.size() >= MAX_PENDING_REQUESTS) {
            throw new IllegalStateException("MCP pending 请求超过上限");
        }
        long id = nextId.getAndIncrement();
        CompletableFuture<JsonNode> fut = new CompletableFuture<>();
        pending.put(id, fut);
        ObjectNode msg = MAPPER.createObjectNode();
        msg.put("jsonrpc", "2.0");
        msg.put("id", id);
        msg.put("method", method);
        if (Objects.nonNull(params)) {
            msg.set("params", params);
        }
        try {
            writeFrame(msg);
        } catch (Exception e) {
            pending.remove(id, fut);
            fut.completeExceptionally(e);
            throw e;
        }
        return fut;
    }

    private void notify(String method, JsonNode params) throws Exception {
        ObjectNode msg = MAPPER.createObjectNode();
        msg.put("jsonrpc", "2.0");
        msg.put("method", method);
        if (Objects.nonNull(params)) {
            msg.set("params", params);
        }
        writeFrame(msg);
    }

    private synchronized void writeFrame(JsonNode msg) throws Exception {
        byte[] body = MAPPER.writeValueAsBytes(msg);
        OutputStream out = process.getOutputStream();
        String header = "Content-Length: " + body.length + "\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }

    private void readLoop() {
        try (InputStream raw = new BufferedInputStream(process.getInputStream())) {
            while (!closed) {
                JsonNode msg = readOneMessage(raw);
                if (Objects.isNull(msg)) {
                    break;
                }
                handleMessage(msg);
            }
        } catch (Exception e) {
            if (!closed) {
                log.warn("MCP[{}] 读循环结束: {}", serverId, e.getMessage());
            }
        } finally {
            pending.forEach((id, fut) -> fut.completeExceptionally(new IllegalStateException("MCP 连接关闭")));
            pending.clear();
        }
    }

    /** Content-Length 帧；若首行是 `{` 则按 NDJSON 一行解析 */
    private JsonNode readOneMessage(InputStream in) throws Exception {
        ByteArrayOutputStream lineBuf = new ByteArrayOutputStream();
        int contentLength = -1;
        while (true) {
            int b = in.read();
            if (b < 0) {
                return null;
            }
            if (b == '\n') {
                String line = lineBuf.toString(StandardCharsets.UTF_8).replace("\r", "").trim();
                lineBuf.reset();
                if (line.isEmpty()) {
                    if (contentLength >= 0) {
                        if (contentLength > maxFrameBytes) {
                            throw new IllegalStateException("MCP frame 超过上限: " + contentLength);
                        }
                        byte[] body = in.readNBytes(contentLength);
                        if (body.length < contentLength) {
                            return null;
                        }
                        return MAPPER.readTree(body);
                    }
                    continue;
                }
                if (line.regionMatches(true, 0, "Content-Length:", 0, 15)) {
                    if (line.length() > MAX_HEADER_BYTES) {
                        throw new IllegalStateException("MCP header 超过上限");
                    }
                    try {
                        contentLength = Integer.parseInt(line.substring(15).trim());
                    } catch (NumberFormatException e) {
                        throw new IllegalStateException("非法 MCP Content-Length", e);
                    }
                    if (contentLength < 0 || contentLength > maxFrameBytes) {
                        throw new IllegalStateException("MCP frame 超过上限: " + contentLength);
                    }
                    continue;
                }
                if (line.startsWith("{")) {
                    if (line.getBytes(StandardCharsets.UTF_8).length > maxFrameBytes) {
                        throw new IllegalStateException("MCP NDJSON frame 超过上限");
                    }
                    return MAPPER.readTree(line);
                }
                // 其它头忽略
            } else {
                if (lineBuf.size() >= maxFrameBytes + MAX_HEADER_BYTES) {
                    throw new IllegalStateException("MCP frame/header 超过上限");
                }
                lineBuf.write(b);
            }
        }
    }

    private void handleMessage(JsonNode msg) {
        if (Objects.isNull(msg)) {
            return;
        }
        if (msg.has("id") && (msg.has("result") || msg.has("error"))) {
            long id = msg.get("id").asLong();
            CompletableFuture<JsonNode> fut = pending.remove(id);
            if (Objects.isNull(fut)) {
                return;
            }
            if (msg.has("error")) {
                fut.completeExceptionally(new IllegalStateException(msg.get("error").toString()));
            } else {
                fut.complete(msg.get("result"));
            }
            return;
        }
        log.debug("MCP[{}] 通知: {}", serverId, msg.path("method").asText());
    }

    private void drainStderr() {
        try (InputStream err = process.getErrorStream()) {
            err.transferTo(OutputStream.nullOutputStream());
        } catch (Exception ignored) {}
    }

    private Map<String, Object> schemaToParams(JsonNode schema) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (Objects.isNull(schema) || !schema.isObject()) {
            return params;
        }
        JsonNode props = schema.get("properties");
        JsonNode required = schema.get("required");
        java.util.Set<String> req = new java.util.HashSet<>();
        if (required instanceof ArrayNode arr) {
            arr.forEach(n -> req.add(n.asText()));
        }
        if (Objects.nonNull(props) && props.isObject()) {
            props.fields().forEachRemaining(e -> {
                JsonNode def = e.getValue();
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("type", def.path("type").asText("string"));
                if (def.has("description")) {
                    p.put("description", def.get("description").asText());
                }
                if (req.contains(e.getKey())) {
                    p.put("required", true);
                }
                params.put(e.getKey(), p);
            });
        }
        return params;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = Objects.isNull(n) ? null : n.get(field);
        return Objects.isNull(v) || v.isNull() ? null : v.asText();
    }

    @Override
    public void close() {
        closed = true;
        try { process.destroyForcibly(); } catch (Exception ignored) {}
        pending.forEach((id, fut) -> fut.completeExceptionally(new IllegalStateException("closed")));
        pending.clear();
    }

    public record McpToolDef(String name, String description, Map<String, Object> parameters) {}
}
