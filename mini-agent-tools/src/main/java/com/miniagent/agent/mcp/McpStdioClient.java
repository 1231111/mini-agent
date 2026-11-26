package com.miniagent.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.miniagent.agent.tool.ProcessEnv;
import com.miniagent.agent.tool.ToolSchemaContract;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.Semaphore;
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
    private static final int MAX_TOOLS_LIST_PAGES = 100;
    private static final int MAX_DISCOVERED_TOOLS = 2_000;
    private static final int DEFAULT_WRITE_TIMEOUT_SECONDS = 10;
    private static final int CANCELLATION_WRITE_TIMEOUT_SECONDS = 2;

    private record PendingRequest(long id, String method, CompletableFuture<JsonNode> response) {}

    private final String serverId;
    private final Process process;
    private final int maxFrameBytes;
    private final int requestTimeoutSeconds;
    private final int writeTimeoutSeconds;
    private final AtomicLong nextId = new AtomicLong(1);
    private final ConcurrentHashMap<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final Semaphore pendingSlots = new Semaphore(MAX_PENDING_REQUESTS);
    /** A stalled child must not block the caller thread while writing a large stdio frame. */
    private final ThreadPoolExecutor writer;
    private volatile boolean closed;

    public McpStdioClient(String serverId, Process process) {
        this(serverId, process, DEFAULT_MAX_FRAME_BYTES, 120);
    }

    public McpStdioClient(String serverId, Process process,
                          int maxFrameBytes, int requestTimeoutSeconds) {
        this(serverId, process, maxFrameBytes, requestTimeoutSeconds, DEFAULT_WRITE_TIMEOUT_SECONDS);
    }

    McpStdioClient(String serverId, Process process, int maxFrameBytes,
                   int requestTimeoutSeconds, int writeTimeoutSeconds) {
        this.serverId = serverId;
        this.process = process;
        this.maxFrameBytes = Math.max(64 * 1024,
                Math.min(64 * 1024 * 1024, maxFrameBytes));
        this.requestTimeoutSeconds = Math.max(1, Math.min(600, requestTimeoutSeconds));
        this.writeTimeoutSeconds = Math.max(1, Math.min(60, writeTimeoutSeconds));
        this.writer = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(64),
                Thread.ofVirtual().name("mcp-stdio-writer-" + serverId).factory(),
                new ThreadPoolExecutor.AbortPolicy());
        Thread.ofVirtual().name("mcp-stdio-" + serverId).start(this::readLoop);
        Thread.ofVirtual().name("mcp-stderr-" + serverId).start(this::drainStderr);
    }

    public static McpStdioClient start(McpProperties.Server cfg) throws Exception {
        if (StringUtils.isBlank(cfg.getCommand())) {
            throw new IllegalArgumentException("MCP server " + cfg.getId() + " 缺少 command");
        }
        Path executable = resolveAllowedExecutable(cfg.getCommand(), cfg.getAllowedExecutable());
        List<String> cmd = new ArrayList<>();
        cmd.add(executable.toString());
        if (Objects.nonNull(cfg.getArgs())) {
            cmd.addAll(cfg.getArgs());
        }
        ProcessBuilder pb = new ProcessBuilder(cmd);
        // 环境沙箱：先收敛到白名单，再合并本 server 显式声明的 env。
        // 继承整份环境意味着任意 MCP 服务（其工具由服务方定义、模型可直接调用）
        // 都能读到 agent 的模型 key 与数据库口令；需要 token 的服务请在配置的 env 里显式声明。
        ProcessEnv.sanitizeMcp(pb, cfg.getEnv());
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

    /** Resolve and check the configured executable against its explicit canonical allowlist path. */
    static Path resolveAllowedExecutable(String command, String allowedExecutable) throws Exception {
        if (StringUtils.isBlank(command) || StringUtils.isBlank(allowedExecutable)) {
            throw new IllegalArgumentException("MCP command and allowed-executable must both be configured");
        }
        Path requested = Path.of(command.trim());
        Path approved = Path.of(allowedExecutable.trim());
        if (!requested.isAbsolute() || !approved.isAbsolute()) {
            throw new IllegalArgumentException("MCP command and allowed-executable must be absolute paths");
        }
        Path realRequested = requested.toRealPath();
        Path realApproved = approved.toRealPath();
        if (!Files.isRegularFile(realRequested) || !Files.isRegularFile(realApproved)
                || !realRequested.equals(realApproved)) {
            throw new IllegalArgumentException("MCP executable is not the configured allowlisted file");
        }
        return realRequested;
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
        List<McpToolDef> out = new ArrayList<>();
        java.util.Set<String> requestedCursors = new java.util.HashSet<>();
        long listingDeadline = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(Math.min(requestTimeoutSeconds, 120));
        String cursor = "";
        for (int page = 0; page < MAX_TOOLS_LIST_PAGES; page++) {
            if (!cursor.isBlank() && !requestedCursors.add(cursor)) {
                throw new IllegalStateException("MCP tools/list 重复返回分页游标，拒绝无限分页: " + serverId);
            }
            ObjectNode params = MAPPER.createObjectNode();
            if (!cursor.isBlank()) params.put("cursor", cursor);
            long remainingNanos = listingDeadline - System.nanoTime();
            if (remainingNanos <= 0) {
                throw new TimeoutException("MCP tools/list discovery deadline exceeded: " + serverId);
            }
            int pageTimeout = (int) Math.max(1L, Math.min(30L,
                    TimeUnit.NANOSECONDS.toSeconds(remainingNanos)));
            JsonNode result = await(request("tools/list", params), pageTimeout);
            JsonNode tools = Objects.isNull(result) ? null : result.get("tools");
            if (Objects.nonNull(tools) && !tools.isArray()) {
                throw new IllegalStateException("MCP tools/list tools 字段不是数组: " + serverId);
            }
            if (tools instanceof ArrayNode array) {
                for (JsonNode t : array) {
                    String name = text(t, "name");
                    if (StringUtils.isBlank(name)) continue;
                    String desc = text(t, "description");
                    Map<String, Object> toolParams = schemaToParams(t.get("inputSchema"));
                    out.add(new McpToolDef(name, Optional.ofNullable(desc).orElse(""), toolParams));
                    if (out.size() > MAX_DISCOVERED_TOOLS) {
                        throw new IllegalStateException("MCP tools/list 超过工具数量上限 "
                                + MAX_DISCOVERED_TOOLS + ": " + serverId);
                    }
                }
            }
            String next = text(result, "nextCursor");
            if (StringUtils.isBlank(next)) return List.copyOf(out);
            cursor = next;
        }
        throw new IllegalStateException("MCP tools/list 超过分页上限 " + MAX_TOOLS_LIST_PAGES + ": " + serverId);
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

    public boolean isHealthy() {
        return !closed && process.isAlive();
    }

    private JsonNode await(PendingRequest request, int timeoutSeconds)
            throws Exception {
        try {
            return request.response().get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException timeout) {
            cancelAndInvalidate(request, "request timed out");
            throw new TimeoutException("MCP " + request.method() + " timed out after "
                    + timeoutSeconds + "s; cancellation was sent when possible and the stdio process was restarted; "
                    + "remote side effects may already have happened");
        } catch (InterruptedException interrupted) {
            cancelAndInvalidate(request, "caller interrupted");
            Thread.currentThread().interrupt();
            throw interrupted;
        }
    }

    private void cancelAndInvalidate(PendingRequest request, String reason) {
        pending.remove(request.id(), request.response());
        request.response().cancel(true);
        if (!closed) {
            ObjectNode params = MAPPER.createObjectNode();
            params.put("requestId", request.id());
            params.put("reason", reason);
            ObjectNode notification = MAPPER.createObjectNode();
            notification.put("jsonrpc", "2.0");
            notification.put("method", "notifications/cancelled");
            notification.set("params", params);
            try {
                writeFrame(notification, CANCELLATION_WRITE_TIMEOUT_SECONDS);
                // The writer is single-threaded; waiting for writeFrame's future means
                // the notification bytes reached the child stream before teardown begins.
            } catch (Exception sendFailure) {
                log.debug("MCP[{}] 无法发送取消通知；将关闭失效 stdio 连接: {}", serverId,
                        sendFailure.getMessage());
            }
        }
        // Cancellation in MCP is advisory and has no acknowledgement. Retire this child
        // so a server that ignores the notification cannot keep doing local work forever.
        terminateConnection("MCP request cancelled; remote outcome remains unknown");
    }

    private PendingRequest request(String method, JsonNode params) throws Exception {
        if (closed) {
            throw new IllegalStateException("MCP 连接已关闭");
        }
        if (!pendingSlots.tryAcquire()) {
            throw new IllegalStateException("MCP pending 请求超过上限");
        }
        long id = nextId.getAndIncrement();
        CompletableFuture<JsonNode> fut = new CompletableFuture<>();
        fut.whenComplete((ignored, failure) -> pendingSlots.release());
        pending.put(id, fut);
        ObjectNode msg = MAPPER.createObjectNode();
        msg.put("jsonrpc", "2.0");
        msg.put("id", id);
        msg.put("method", method);
        if (Objects.nonNull(params)) msg.set("params", params);
        try {
            writeFrame(msg, writeTimeoutSeconds);
        } catch (Exception e) {
            pending.remove(id, fut);
            fut.completeExceptionally(e);
            throw e;
        }
        return new PendingRequest(id, method, fut);
    }

    private String formatCallResult(JsonNode result) throws Exception {
        if (Objects.isNull(result) || result.isNull()) {
            // A JSON-RPC null result is not a tools/call acknowledgement. Let
            // the bridge retain the uncertain outcome rather than succeed on
            // the literal text "null" or treat a missing reply as a safe failure.
            throw new IllegalStateException("MCP tools/call 返回空结果，无法确认操作状态");
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

    private void notify(String method, JsonNode params) throws Exception {
        ObjectNode msg = MAPPER.createObjectNode();
        msg.put("jsonrpc", "2.0");
        msg.put("method", method);
        if (Objects.nonNull(params)) {
            msg.set("params", params);
        }
        writeFrame(msg, writeTimeoutSeconds);
    }

    private void writeFrame(JsonNode msg, int timeoutSeconds) throws Exception {
        if (closed) throw new IllegalStateException("MCP 连接已关闭");
        byte[] body = MAPPER.writeValueAsBytes(msg);
        if (body.length > maxFrameBytes) {
            throw new IllegalArgumentException("MCP 请求帧超过配置上限: " + body.length);
        }
        Future<?> write;
        try {
            write = writer.submit(() -> {
                OutputStream out = process.getOutputStream();
                String header = "Content-Length: " + body.length + "\r\n\r\n";
                out.write(header.getBytes(StandardCharsets.UTF_8));
                out.write(body);
                out.flush();
                return null;
            });
        } catch (java.util.concurrent.RejectedExecutionException saturated) {
            terminateConnection("MCP stdio writer queue saturated");
            throw new IllegalStateException("MCP stdio writer queue saturated; connection closed", saturated);
        }
        try {
            write.get(Math.max(1, timeoutSeconds), TimeUnit.SECONDS);
        } catch (TimeoutException timeout) {
            write.cancel(true);
            terminateConnection("MCP stdio write timed out");
            throw new TimeoutException("MCP stdio 写入超时；连接已关闭");
        } catch (InterruptedException interrupted) {
            write.cancel(true);
            terminateConnection("MCP stdio write interrupted");
            throw interrupted;
        } catch (ExecutionException | java.util.concurrent.RejectedExecutionException failure) {
            terminateConnection("MCP stdio write failed");
            Throwable cause = failure instanceof ExecutionException execution
                    ? execution.getCause() : failure;
            if (cause instanceof java.io.IOException io) {
                throw new TimeoutException("MCP stdio 写入失败/管道不可用，连接已关闭: " + io.getMessage());
            }
            if (cause instanceof Exception exception) throw exception;
            throw new IllegalStateException("MCP stdio 写入失败", cause);
        }
    }

    private void readLoop() {
        try (InputStream raw = new BufferedInputStream(process.getInputStream())) {
            while (!closed) {
                JsonNode msg = readOneMessage(raw, maxFrameBytes);
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
            // A malformed or oversized frame makes this protocol stream unusable.
            // Terminate the child as well as failing waiters so a broken MCP server
            // cannot remain orphaned after its reader has exited.
            boolean wasClosed = closed;
            terminateConnection(wasClosed ? "MCP reader stopped" : "MCP reader stopped after protocol failure");
            pending.forEach((id, fut) -> fut.completeExceptionally(new IllegalStateException("MCP 连接关闭")));
            pending.clear();
        }
    }

    /** Content-Length 帧；若首行是 `{` 则按 NDJSON 一行解析 */
    static JsonNode readOneMessage(InputStream in, int maxFrameBytes) throws Exception {
        int boundedFrameBytes = Math.max(1, Math.min(64 * 1024 * 1024, maxFrameBytes));
        ByteArrayOutputStream lineBuf = new ByteArrayOutputStream();
        int contentLength = -1;
        int headerBytes = 0;
        boolean jsonLine = false;
        boolean firstNonWhitespaceSeen = false;
        while (true) {
            int b = in.read();
            if (b < 0) {
                return null;
            }
            if (b == '\n') {
                String line = lineBuf.toString(StandardCharsets.UTF_8).replace("\r", "").trim();
                int rawLineBytes = lineBuf.size() + 1;
                lineBuf.reset();
                if (line.isEmpty()) {
                    if (contentLength >= 0) {
                        if (contentLength > boundedFrameBytes) {
                            throw new IllegalStateException("MCP frame 超过上限: " + contentLength);
                        }
                        byte[] body = in.readNBytes(contentLength);
                        if (body.length < contentLength) {
                            return null;
                        }
                        return MAPPER.readTree(body);
                    }
                    headerBytes += rawLineBytes;
                    if (headerBytes > MAX_HEADER_BYTES) {
                        throw new IllegalStateException("MCP header 超过上限");
                    }
                    jsonLine = false;
                    firstNonWhitespaceSeen = false;
                    continue;
                }
                if (line.regionMatches(true, 0, "Content-Length:", 0, 15)) {
                    headerBytes += rawLineBytes;
                    if (headerBytes > MAX_HEADER_BYTES) {
                        throw new IllegalStateException("MCP header 超过上限");
                    }
                    if (contentLength >= 0) {
                        throw new IllegalStateException("重复 MCP Content-Length");
                    }
                    try {
                        contentLength = Integer.parseInt(line.substring(15).trim());
                    } catch (NumberFormatException e) {
                        throw new IllegalStateException("非法 MCP Content-Length", e);
                    }
                    if (contentLength < 0 || contentLength > boundedFrameBytes) {
                        throw new IllegalStateException("MCP frame 超过上限: " + contentLength);
                    }
                    jsonLine = false;
                    firstNonWhitespaceSeen = false;
                    continue;
                }
                if (line.startsWith("{")) {
                    if (line.getBytes(StandardCharsets.UTF_8).length > boundedFrameBytes) {
                        throw new IllegalStateException("MCP NDJSON frame 超过上限");
                    }
                    return MAPPER.readTree(line);
                }
                // Ignore other bounded headers. Cumulative accounting prevents
                // arbitrarily many small header lines from keeping the reader busy.
                headerBytes += rawLineBytes;
                if (headerBytes > MAX_HEADER_BYTES) {
                    throw new IllegalStateException("MCP header 超过上限");
                }
                jsonLine = false;
                firstNonWhitespaceSeen = false;
            } else {
                lineBuf.write(b);
                if (!firstNonWhitespaceSeen && !Character.isWhitespace((char) b) && b != '\r') {
                    firstNonWhitespaceSeen = true;
                    jsonLine = b == '{';
                }
                int currentLimit = jsonLine ? boundedFrameBytes : MAX_HEADER_BYTES;
                if (lineBuf.size() > currentLimit) {
                    throw new IllegalStateException(jsonLine
                            ? "MCP NDJSON frame 超过上限" : "MCP header line 超过上限");
                }
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
        return ToolSchemaContract.fromJsonSchema(schema);
    }

    private void terminateConnection(String reason) {
        if (closed) return;
        closed = true;
        try { writer.shutdownNow(); } catch (Exception ignored) {}
        try { process.destroyForcibly(); } catch (Exception ignored) {}
        pending.forEach((id, future) -> future.completeExceptionally(new IllegalStateException(reason)));
        pending.clear();
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = Objects.isNull(n) ? null : n.get(field);
        return Objects.isNull(v) || v.isNull() ? null : v.asText();
    }

    @Override
    public void close() {
        terminateConnection("closed");
    }

    public record McpToolDef(String name, String description, Map<String, Object> parameters) {}
}
