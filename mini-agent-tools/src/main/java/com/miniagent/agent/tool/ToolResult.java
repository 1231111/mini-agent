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
                if ("unknown".equals(status)) {
                    return unknown(node.path("error").asText("工具结果未知"), rawText);
                }
                if ("timeout".equals(status)) {
                    return failure(ToolErrorCode.TIMEOUT, node.path("error").asText("工具超时"), true);
                }
                if ("cancelled".equals(status) || "canceled".equals(status)) {
                    return failure(ToolErrorCode.CANCELLED, node.path("error").asText("工具已取消"), false);
                }
                if (failed) {
                    String message = node.path("error").asText("工具执行失败");
                    ToolErrorCode code = node.has("errorCode")
                            ? parseErrorCode(node.path("errorCode").asText(""))
                            : classifyLegacyFailure(message);
                    return failure(code, message, isRetriable(code));
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
        ToolErrorCode code = classifyLegacyFailure(lower);
        if (code != ToolErrorCode.NONE) {
            return failure(code, rawText, isRetriable(code));
        }
        if (lower.startsWith("错误") || lower.startsWith("error:")) {
            return failure(ToolErrorCode.EXECUTION_FAILED, rawText, false);
        }
        return success(rawText);
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
