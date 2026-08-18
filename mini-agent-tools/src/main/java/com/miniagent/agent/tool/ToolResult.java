package com.miniagent.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;

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
        ToolErrorCode actual = code == null ? ToolErrorCode.INTERNAL_ERROR : code;
        ToolStatus status = actual == ToolErrorCode.TIMEOUT ? ToolStatus.TIMEOUT
                : actual == ToolErrorCode.CANCELLED ? ToolStatus.CANCELLED : ToolStatus.FAILED;
        String msg = Objects.requireNonNullElse(message, "工具执行失败");
        String raw = "{\"success\":false,\"status\":\"" + status.name().toLowerCase(Locale.ROOT)
                + "\",\"errorCode\":\"" + actual + "\",\"error\":\""
                + msg.replace("\\", "\\\\").replace("\"", "'") + "\",\"retriable\":" + retriable + "}";
        return new ToolResult(status, actual, msg, data(raw), retriable, "", raw);
    }

    public static ToolResult unknown(String message, String rawText) {
        String msg = Objects.requireNonNullElse(message, "工具结果未知");
        String raw = rawText == null ? "{\"success\":false,\"status\":\"unknown\",\"errorCode\":\"OUTCOME_UNKNOWN\",\"error\":\""
                + msg.replace("\"", "'") + "\"}" : rawText;
        return new ToolResult(ToolStatus.UNKNOWN, ToolErrorCode.OUTCOME_UNKNOWN, msg, data(raw), false, "", raw);
    }

    public static ToolResult fromLegacy(String rawText) {
        if (rawText == null || rawText.isBlank()) return failure(ToolErrorCode.EMPTY_RESULT, "工具返回空结果", false);
        try {
            JsonNode node = JSON.readTree(rawText);
            if (node != null && node.isObject()) {
                String status = node.path("status").asText("").toLowerCase(Locale.ROOT);
                boolean failed = node.has("error") || (node.has("success") && !node.path("success").asBoolean(true));
                if ("unknown".equals(status)) return unknown(node.path("error").asText("工具结果未知"), rawText);
                if ("timeout".equals(status)) return failure(ToolErrorCode.TIMEOUT, node.path("error").asText("工具超时"), true);
                if ("cancelled".equals(status) || "canceled".equals(status)) return failure(ToolErrorCode.CANCELLED, node.path("error").asText("工具已取消"), false);
                if (failed) {
                    String message = node.path("error").asText("工具执行失败");
                    ToolErrorCode code = node.has("errorCode")
                            ? parseErrorCode(node.path("errorCode").asText(""))
                            : classifyLegacyFailure(message);
                    return failure(code, message, isRetriable(code));
                }
            }
        } catch (Exception ignored) {
            // Plain text remains a supported legacy result.
        }
        String lower = rawText.trim().toLowerCase(Locale.ROOT);
        ToolErrorCode code = classifyLegacyFailure(lower);
        if (code != ToolErrorCode.NONE) {
            return failure(code, rawText, isRetriable(code));
        }
        if (lower.startsWith("exit_code=") && !lower.startsWith("exit_code=0")) {
            return failure(ToolErrorCode.EXECUTION_FAILED, rawText, false);
        }
        if (lower.startsWith("错误") || lower.startsWith("error:")) {
            return failure(ToolErrorCode.EXECUTION_FAILED, rawText, false);
        }
        return success(rawText);
    }

    private static ToolErrorCode parseErrorCode(String raw) {
        if (raw == null || raw.isBlank()) return ToolErrorCode.EXECUTION_FAILED;
        try {
            return ToolErrorCode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
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
        if (lower.contains("503") || lower.contains("502") || lower.contains("504")
                || lower.contains("unavailable") || lower.contains("bad gateway")
                || lower.contains("gateway timeout")) {
            return ToolErrorCode.DEPENDENCY_UNAVAILABLE;
        }
        return ToolErrorCode.NONE;
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
