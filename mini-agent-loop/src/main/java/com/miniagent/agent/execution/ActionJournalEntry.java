package com.miniagent.agent.execution;

import java.util.Objects;

/** Journal 只保存哈希和有限诊断，避免持久化敏感工具结果。 */
public record ActionJournalEntry(ActionJournalKey key, String toolName, String argumentsHash,
                                 ActionExecutionStatus status, int attempt,
                                 long timestampEpochMillis, String errorCode,
                                 String message, String resultDigest) {
    public ActionJournalEntry {
        key = Objects.requireNonNull(key, "key");
        toolName = Objects.requireNonNullElse(toolName, "");
        argumentsHash = Objects.requireNonNullElse(argumentsHash, "");
        status = Objects.requireNonNull(status, "status");
        attempt = Math.max(0, attempt);
        timestampEpochMillis = timestampEpochMillis > 0 ? timestampEpochMillis : System.currentTimeMillis();
        errorCode = Objects.requireNonNullElse(errorCode, "");
        message = truncate(Objects.requireNonNullElse(message, ""), 1000);
        resultDigest = Objects.requireNonNullElse(resultDigest, "");
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
