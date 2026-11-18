package com.miniagent.agent.mcp;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/** Bounds automatic child-process restart attempts per configured server. */
final class McpReconnectBudget {
    private final Map<String, ArrayDeque<Long>> attemptsByServer = new HashMap<>();

    synchronized boolean allow(String serverId, long nowMillis, long windowMillis, int maxAttempts) {
        if (serverId == null || maxAttempts < 1 || windowMillis < 1) return false;
        ArrayDeque<Long> attempts = attemptsByServer.computeIfAbsent(serverId, ignored -> new ArrayDeque<>());
        long cutoff = nowMillis - windowMillis;
        while (!attempts.isEmpty() && attempts.peekFirst() <= cutoff) attempts.removeFirst();
        if (attempts.size() >= maxAttempts) return false;
        attempts.addLast(nowMillis);
        if (attempts.isEmpty()) attemptsByServer.remove(serverId);
        return true;
    }

    synchronized int attemptsInWindow(String serverId, long nowMillis, long windowMillis) {
        ArrayDeque<Long> attempts = attemptsByServer.get(serverId);
        if (attempts == null) return 0;
        long cutoff = nowMillis - windowMillis;
        while (!attempts.isEmpty() && attempts.peekFirst() <= cutoff) attempts.removeFirst();
        if (attempts.isEmpty()) {
            attemptsByServer.remove(serverId);
            return 0;
        }
        return attempts.size();
    }
}
