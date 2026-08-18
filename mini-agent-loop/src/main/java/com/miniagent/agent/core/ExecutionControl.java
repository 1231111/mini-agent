package com.miniagent.agent.core;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** 会话执行控制面：取消、deadline、心跳和资源预算都在这里收敛。 */
@Component
public class ExecutionControl {
    public enum StopReason { NONE, CANCELLED, DEADLINE_EXCEEDED, TOOL_BUDGET_EXCEEDED, TOKEN_BUDGET_EXCEEDED }

    public static final class Lease {
        private final long deadlineEpochMillis;
        private final int maxToolCalls;
        private final long maxEstimatedTokens;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicInteger toolCalls = new AtomicInteger();
        private final AtomicLong estimatedTokens = new AtomicLong();
        private final AtomicLong heartbeatEpochMillis = new AtomicLong(System.currentTimeMillis());

        private Lease(long deadlineEpochMillis, int maxToolCalls, long maxEstimatedTokens) {
            this.deadlineEpochMillis = deadlineEpochMillis;
            this.maxToolCalls = maxToolCalls;
            this.maxEstimatedTokens = maxEstimatedTokens;
        }
    }

    private final Map<String, Lease> leases = new ConcurrentHashMap<>();
    private final long deadlineMillis;
    private final int maxToolCalls;
    private final long maxEstimatedTokens;

    public ExecutionControl(@Value("${agent.execution.deadline-ms:1800000}") long deadlineMillis,
                            @Value("${agent.execution.max-tool-calls:120}") int maxToolCalls,
                            @Value("${agent.execution.max-estimated-tokens:240000}") long maxEstimatedTokens) {
        this.deadlineMillis = Math.max(1_000L, deadlineMillis);
        this.maxToolCalls = Math.max(1, maxToolCalls);
        this.maxEstimatedTokens = Math.max(1_000L, maxEstimatedTokens);
    }

    public Lease start(String sessionId) {
        String key = key(sessionId);
        Lease lease = new Lease(System.currentTimeMillis() + deadlineMillis, maxToolCalls, maxEstimatedTokens);
        leases.put(key, lease);
        return lease;
    }
    public void finish(String sessionId) { leases.remove(key(sessionId)); }
    public void cancel(String sessionId) { leases.computeIfAbsent(key(sessionId), ignored -> fallbackLease()).cancelled.set(true); }
    public boolean isCancelled(String sessionId) { return lease(sessionId).cancelled.get(); }
    public void heartbeat(String sessionId) { lease(sessionId).heartbeatEpochMillis.set(System.currentTimeMillis()); }
    public long heartbeatEpochMillis(String sessionId) { return lease(sessionId).heartbeatEpochMillis.get(); }

    public StopReason beforeTool(String sessionId) {
        Lease lease = lease(sessionId);
        heartbeat(sessionId);
        if (lease.cancelled.get()) return StopReason.CANCELLED;
        if (System.currentTimeMillis() > lease.deadlineEpochMillis) return StopReason.DEADLINE_EXCEEDED;
        return lease.toolCalls.incrementAndGet() > lease.maxToolCalls ? StopReason.TOOL_BUDGET_EXCEEDED : StopReason.NONE;
    }

    public StopReason afterModelTokens(String sessionId, long estimated) {
        Lease lease = lease(sessionId);
        heartbeat(sessionId);
        if (lease.cancelled.get()) return StopReason.CANCELLED;
        if (System.currentTimeMillis() > lease.deadlineEpochMillis) return StopReason.DEADLINE_EXCEEDED;
        return lease.estimatedTokens.addAndGet(Math.max(0, estimated)) > lease.maxEstimatedTokens
                ? StopReason.TOKEN_BUDGET_EXCEEDED : StopReason.NONE;
    }

    private Lease lease(String sessionId) { return leases.computeIfAbsent(key(sessionId), ignored -> fallbackLease()); }
    private Lease fallbackLease() { return new Lease(System.currentTimeMillis() + deadlineMillis, maxToolCalls, maxEstimatedTokens); }
    private static String key(String sessionId) { return sessionId == null || sessionId.isBlank() ? "anonymous" : sessionId; }
}
