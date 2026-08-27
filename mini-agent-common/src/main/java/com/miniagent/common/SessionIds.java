package com.miniagent.common;

import java.util.regex.Pattern;

/** Validation shared by database, cache, SSE and filesystem session namespaces. */
public final class SessionIds {

    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,99}");

    private SessionIds() {}

    public static boolean isValid(String sessionId) {
        return sessionId != null && SAFE.matcher(sessionId).matches();
    }

    public static String requireValid(String sessionId) {
        if (!isValid(sessionId)) {
            throw new IllegalArgumentException(
                    "sessionId must be 1-100 characters using letters, digits, '.', '_' or '-'");
        }
        return sessionId;
    }
}
