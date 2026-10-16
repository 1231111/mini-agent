package com.miniagent.common;

/** Validation shared by database, cache, SSE and filesystem session namespaces. */
public final class SessionIds {

    private SessionIds() {}

    public static boolean isValid(String sessionId) {
        // 规则集中在 SafeSegments（同一套字符集也被文件名/目录名复用），这里只保留会话语义。
        return SafeSegments.isValid(sessionId);
    }

    public static String requireValid(String sessionId) {
        if (!isValid(sessionId)) {
            throw new IllegalArgumentException(
                    "sessionId must be 1-100 characters using letters, digits, '.', '_' or '-'");
        }
        return sessionId;
    }
}
