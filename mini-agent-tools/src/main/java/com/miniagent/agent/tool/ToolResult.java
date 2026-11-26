package com.miniagent.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import com.miniagent.common.MessageConstants;

import java.util.Locale;
import java.util.Objects;

/** 兼容当前文本协议的结构化工具结果。 */
public record ToolResult(ToolStatus status, ToolErrorCode errorCode, String message,
                         JsonNode data, boolean retriable, String evidence, String rawText) {
    private static final ObjectMapper JSON = new ObjectMapper();

    public ToolResult {
        status = status == null ? ToolStatus.UNKNOWN : status;
        errorCode = errorCode == null ? ToolErrorCode.INTERNAL_ERROR : errorCode;
        message = Objects.requireNonNullElse(message, "");
        evidence = Objects.requireNonNullElse(evidence, "");
        rawText = Objects.requireNonNullElse(rawText, "");
    }

    public boolean isSuccess() { return status == ToolStatus.SUCCESS; }
    public String legacyText() { return rawText; }

    public static ToolResult success(String rawText) {
        String raw = Objects.requireNonNullElse(rawText, "");
        return new ToolResult(ToolStatus.SUCCESS, ToolErrorCode.NONE, "", data(raw), false, raw, raw);
    }

    public static ToolResult failure(ToolErrorCode code, String message, boolean retriable) {
        return failure(code, message, retriable, null);
    }

    /**
     * @param rawTextOverride 保留调用方的原始结果文本；传 {@code null} 时按 {@code message} 重建
     *                        （兼容老调用方）。**能传原文就必须传原文。**
     *
     * <p>重建会把服务层附带的额外字段静默丢掉。实测（2026-10-15）：
     * {@code SongGenerationService} 返回 {@code {"error":"歌词生成请求失败","hint":"HTTP 400：<响应体前 300 字>"}}，
     * 旧实现只取 {@code error} 重建，于是模型只看得到「歌词生成请求失败」——
     * 服务端到底说了什么全没了，用户连着几轮问「为什么没有具体错误码」的答案就在这里。
     * 连带地 {@link #classifyLegacyFailure} 拿不到状态码，把 429 / 503 也判成不可重试。</p>
     */
    public static ToolResult failure(ToolErrorCode code, String message, boolean retriable,
                                     String rawTextOverride) {
        ToolErrorCode actual = code == null ? ToolErrorCode.INTERNAL_ERROR : code;
        ToolStatus status = actual == ToolErrorCode.OUTCOME_UNKNOWN ? ToolStatus.UNKNOWN
                : actual == ToolErrorCode.TIMEOUT ? ToolStatus.TIMEOUT
                : actual == ToolErrorCode.CANCELLED ? ToolStatus.CANCELLED : ToolStatus.FAILED;
        if (actual == ToolErrorCode.OUTCOME_UNKNOWN || actual == ToolErrorCode.CANCELLED) retriable = false;
        String msg = Objects.requireNonNullElse(message, "工具执行失败");
        String raw = rawTextOverride != null && !rawTextOverride.isBlank()
                ? rawTextOverride
                : "{\"success\":false,\"status\":\"" + status.name().toLowerCase(Locale.ROOT)
                + "\",\"errorCode\":\"" + actual + "\",\"error\":"
                + TextNode.valueOf(msg) + ",\"retriable\":" + retriable + "}";
        return new ToolResult(status, actual, msg, data(raw), retriable, "", raw);
    }

    public static ToolResult unknown(String message, String rawText) {
        String msg = Objects.requireNonNullElse(message, "工具结果未知");
        com.fasterxml.jackson.databind.node.ObjectNode normalized = JSON.createObjectNode();
        if (rawText != null) {
            try {
                JsonNode source = JSON.readTree(rawText);
                if (source instanceof com.fasterxml.jackson.databind.node.ObjectNode object) normalized = object.deepCopy();
                else normalized.put("detail", rawText);
            } catch (Exception ignored) { normalized.put("detail", rawText); }
        }
        normalized.put("success", false);
        normalized.put("status", "unknown");
        normalized.put("errorCode", ToolErrorCode.OUTCOME_UNKNOWN.name());
        normalized.put("error", msg);
        normalized.put("retriable", false);
        String raw = normalized.toString();
        return new ToolResult(ToolStatus.UNKNOWN, ToolErrorCode.OUTCOME_UNKNOWN, msg, data(raw), false, "", raw);
    }

    /**
     * 未执行：工具在等用户批准或回答。
     *
     * <p>第三种终态。既不是成功也不是失败，因此 {@code errorCode} 是 {@code NONE}，
     * 且原始文本里<b>不带 error 键</b> —— 这样所有「按文本判失败」的旧逻辑都不会误判它。</p>
     */
    public static ToolResult awaitingUser(String message, String rawText) {
        String msg = Objects.requireNonNullElse(message, "等待用户处理");
        return new ToolResult(ToolStatus.AWAITING_USER, ToolErrorCode.NONE, msg,
                data(Objects.requireNonNullElse(rawText, "")), false, "",
                Objects.requireNonNullElse(rawText, ""));
    }

    public static ToolResult fromLegacy(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return failure(ToolErrorCode.EMPTY_RESULT, "工具返回空结果", false);
        }
        try {
            JsonNode node = JSON.readTree(rawText);
            if (node != null && node.isObject()) {
                String status = node.path("status").asText("").toLowerCase(Locale.ROOT);
                // 必须排在 failed 判定之前：这类结果没有 error 键，落不到 failed 分支，
                // 但如果不管它就会一路掉到最后的 success —— 那是另一个方向的错。
                if (MessageConstants.AWAITING_USER_STATUS.equals(status)) {
                    return awaitingUser(node.path("message").asText(""), rawText);
                }
                boolean failed = (node.has("success") && !node.path("success").asBoolean(true))
                        || (!node.has("success") && node.has("error")
                        && !node.path("error").asText("").isBlank());
                if ("unknown".equals(status)
                        || "OUTCOME_UNKNOWN".equalsIgnoreCase(node.path("errorCode").asText(node.path("error_code").asText("")))) {
                    return unknown(node.path("error").asText("工具结果未知"), rawText);
                }
                if ("timeout".equals(status)) {
                    return failure(ToolErrorCode.TIMEOUT, node.path("error").asText("工具超时"), retryPolicy(node, true),
                            rawText);
                }
                if ("cancelled".equals(status) || "canceled".equals(status)) {
                    return failure(ToolErrorCode.CANCELLED, node.path("error").asText("工具已取消"), false,
                            rawText);
                }
                if (failed) {
                    String err = node.path("error").asText("工具执行失败");
                    // 服务层常把「为什么失败」放进伴生字段：SongGenerationService 用 hint 装
                    // "HTTP <码>：<响应体前 300 字>"，别的工具有用 detail / message 的。
                    // 只读 error 会把它整段丢掉；而且 classifyLegacyFailure 少了状态码
                    // 就认不出 429 / 503，retriable 恒 false。
                    String detail = node.path("hint").asText("");
                    if (detail.isBlank()) {
                        detail = node.path("detail").asText("");
                    }
                    if (detail.isBlank()) {
                        detail = node.path("message").asText("");
                    }
                    String message = detail.isBlank() ? err : err + "；" + detail;
                    ToolErrorCode code = node.has("errorCode") || node.has("error_code")
                            ? parseErrorCode(node.path("errorCode").asText(
                                    node.path("error_code").asText("")))
                            : classifyLegacyFailure(message);
                    if (code == null || code == ToolErrorCode.NONE) code = ToolErrorCode.EXECUTION_FAILED;
                    boolean retriable = code != ToolErrorCode.OUTCOME_UNKNOWN && code != ToolErrorCode.CANCELLED
                            && retryPolicy(node, isRetriable(code));
                    // Normalize legacy failures centrally while preserving unknown
                    // diagnostic fields. Tool consumers always get one contract.
                    com.fasterxml.jackson.databind.node.ObjectNode normalized = node.isObject()
                            ? (com.fasterxml.jackson.databind.node.ObjectNode) node.deepCopy()
                            : JSON.createObjectNode();
                    normalized.put("success", false);
                    normalized.put("status", code == ToolErrorCode.TIMEOUT ? "timeout"
                            : code == ToolErrorCode.CANCELLED ? "cancelled"
                            : code == ToolErrorCode.OUTCOME_UNKNOWN ? "unknown"
                            : status.isBlank() ? "failed" : status);
                    normalized.put("errorCode", code.name());
                    normalized.put("error", message);
                    normalized.put("retriable", retriable);
                    String canonical;
                    try { canonical = JSON.writeValueAsString(normalized); }
                    catch (Exception ignored) { canonical = rawText; }
                    return failure(code, message, retriable, canonical);
                }
                return success(rawText);
            }
        } catch (Exception ignored) {
            // Plain text remains a supported legacy result.
        }
        String trimmed = rawText.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.startsWith(EXEC_CONTRACT_PREFIX)) {
            return fromExecContract(lower, rawText);
        }
        // Legacy tools may return arbitrary successful prose (for example a skill or
        // document that discusses "unavailable" fields). Only interpret known failure
        // phrases when the text is itself shaped like an error response.
        ToolErrorCode code = isExplicitLegacyFailure(trimmed)
                ? classifyLegacyFailure(lower) : ToolErrorCode.NONE;
        if (code != ToolErrorCode.NONE) {
            return failure(code, rawText, isRetriable(code));
        }
        if (lower.startsWith("错误") || lower.startsWith("error:")) {
            return failure(ToolErrorCode.EXECUTION_FAILED, rawText, false);
        }
        return success(rawText);
    }

    /** A producer's explicit boolean is authoritative; only legacy text uses defaults. */
    private static boolean retryPolicy(JsonNode result, boolean defaultValue) {
        JsonNode explicit = result.get("retriable");
        return explicit != null && explicit.isBoolean() ? explicit.booleanValue() : defaultValue;
    }

    /** exec_command 契约行：{@code exit_code=N} 必须在第一行。 */
    private static final String EXEC_CONTRACT_PREFIX = "exit_code=";

    /**
     * 按命令退出码契约判成败，<b>不做关键字猜测</b>。
     *
     * <p>命令输出是任意文本：构建日志里出现 {@code timeout}、grep 命中的代码行里有 {@code 429}、
     * 被搜到的文件名带 {@code 超时}，都是常态。旧路径会拿整段输出跑
     * {@link #classifyLegacyFailure}，把「命令成功但输出里提到超时」读成
     * {@code TIMEOUT} 失败 —— 于是模型重试同一条命令、最后撞上 allFailedRepeated 闸门中止整轮。</p>
     *
     * <p>非 0 但属语义性非错误的退出码（grep/findstr 的 1 等）由 {@code BuiltinTools}
     * 写入 {@link CommandSemantics#TOLERATED_EXIT_PREFIX} 标记，这里读到标记即放行为成功。
     * 写方与读方共用同一常量，避免两边字面量各写一份后改一处失一处。</p>
     */
    private static ToolResult fromExecContract(String lower, String rawText) {
        if (lower.startsWith("exit_code=0") || CommandSemantics.isToleratedExitText(rawText)) {
            return success(rawText);
        }
        return failure(ToolErrorCode.EXECUTION_FAILED, rawText, false);
    }

    private static ToolErrorCode parseErrorCode(String raw) {
        if (raw == null || raw.isBlank()) {
            return ToolErrorCode.EXECUTION_FAILED;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        if (normalized.endsWith("_DEADLINE")) return ToolErrorCode.TIMEOUT;
        if (normalized.endsWith("_CANCELLED") || normalized.endsWith("_CANCELED")
                || normalized.endsWith("_INTERRUPTED")) return ToolErrorCode.CANCELLED;
        try {
            return ToolErrorCode.valueOf(normalized);
        } catch (IllegalArgumentException ignored) {
            return classifyLegacyFailure(raw);
        }
    }

    private static ToolErrorCode classifyLegacyFailure(String raw) {
        String lower = Objects.requireNonNullElse(raw, "").toLowerCase(Locale.ROOT);
        if (lower.contains("timeout") || lower.contains("timed out")
                || lower.contains("超时") || lower.contains("连接超时")) {
            return ToolErrorCode.TIMEOUT;
        }
        if (lower.contains("429") || lower.contains("too many requests")
                || lower.contains("rate limit") || lower.contains("限流")) {
            return ToolErrorCode.RATE_LIMITED;
        }
        if (lower.contains("500") || lower.contains("503") || lower.contains("502") || lower.contains("504")
                || lower.contains("unavailable") || lower.contains("bad gateway")
                || lower.contains("gateway timeout")) {
            return ToolErrorCode.DEPENDENCY_UNAVAILABLE;
        }
        return ToolErrorCode.NONE;
    }

    private static boolean isExplicitLegacyFailure(String raw) {
        String lower = Objects.requireNonNullElse(raw, "").strip().toLowerCase(Locale.ROOT);
        if (lower.startsWith("error:") || lower.startsWith("error ")
                || lower.startsWith("错误:") || lower.startsWith("错误：")
                || lower.startsWith("exception:") || lower.startsWith("exception ")
                || lower.startsWith("failed:") || lower.startsWith("failure:")
                || lower.startsWith("fatal:")) {
            return true;
        }
        if (lower.equals("request timeout") || lower.startsWith("request timeout:")
                || lower.startsWith("request timeout after")
                || lower.startsWith("connection timeout:") || lower.startsWith("connect timeout:")
                || lower.startsWith("read timeout:") || lower.startsWith("timed out:")
                || lower.startsWith("connection timed out:")
                || lower.startsWith("连接超时:") || lower.startsWith("连接超时：")
                || lower.startsWith("请求超时:") || lower.startsWith("请求超时：")
                || lower.startsWith("网络超时:") || lower.startsWith("网络超时：")) {
            return true;
        }
        // Plain HTTP error lines remain a supported legacy protocol. In longer prose,
        // status-like numbers are content and must not be treated as transport failures.
        return lower.matches("(?s)^(?:http\\s*)?(?:429|502|503|504)\\b.*");
    }

    private static boolean isRetriable(ToolErrorCode code) {
        return code == ToolErrorCode.TIMEOUT
                || code == ToolErrorCode.RATE_LIMITED
                || code == ToolErrorCode.DEPENDENCY_UNAVAILABLE;
    }

    private static JsonNode data(String raw) {
        try { return JSON.readTree(raw); }
        catch (Exception ignored) { return TextNode.valueOf(raw); }
    }
}
