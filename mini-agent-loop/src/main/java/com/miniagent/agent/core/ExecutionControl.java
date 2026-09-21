package com.miniagent.agent.core;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** 会话执行控制面：取消、deadline、心跳和资源预算都在这里收敛。 */
@Component
@EnableConfigurationProperties(ExecutionProperties.class)
public class ExecutionControl {
    public enum StopReason {
        NONE,
        CANCELLED,
        DEADLINE_EXCEEDED,
        TOOL_BUDGET_EXCEEDED,
        TOKEN_BUDGET_EXCEEDED,
        TENANT_QUOTA_EXCEEDED
    }

    public static final class Lease {
        private final long deadlineEpochMillis;
        private final int maxToolCalls;
        private final long maxEstimatedTokens;
        private final Long tenantId;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicBoolean tenantQuotaDenied = new AtomicBoolean();
        private final AtomicInteger toolCalls = new AtomicInteger();
        private final AtomicLong estimatedTokens = new AtomicLong();
        private final AtomicLong heartbeatEpochMillis = new AtomicLong(System.currentTimeMillis());

        private Lease(long deadlineEpochMillis, int maxToolCalls, long maxEstimatedTokens,
                      Long tenantId) {
            this.deadlineEpochMillis = deadlineEpochMillis;
            this.maxToolCalls = maxToolCalls;
            this.maxEstimatedTokens = maxEstimatedTokens;
            this.tenantId = tenantId;
        }
    }

    private final Map<String, Lease> leases = new ConcurrentHashMap<>();
    private final long deadlineMillis;
    private final int maxToolCalls;
    private final long maxEstimatedTokens;
    private final Optional<ExecutionSignalStore> signalStore;
    private final Optional<TenantTokenQuota> tenantTokenQuota;

    public ExecutionControl(long deadlineMillis, int maxToolCalls, long maxEstimatedTokens) {
        this(new ExecutionProperties(deadlineMillis, maxToolCalls, maxEstimatedTokens),
                Optional.empty(), Optional.empty());
    }

    public ExecutionControl(long deadlineMillis, int maxToolCalls, long maxEstimatedTokens,
                            Optional<ExecutionSignalStore> signalStore,
                            Optional<TenantTokenQuota> tenantTokenQuota) {
        this(new ExecutionProperties(deadlineMillis, maxToolCalls, maxEstimatedTokens),
                signalStore, tenantTokenQuota);
    }

    @Autowired
    public ExecutionControl(ExecutionProperties properties,
                            Optional<ExecutionSignalStore> signalStore,
                            Optional<TenantTokenQuota> tenantTokenQuota) {
        ExecutionProperties props = properties == null
                ? new ExecutionProperties() : properties;
        this.deadlineMillis = Math.max(1_000L, props.getDeadlineMs());
        this.maxToolCalls = Math.max(1, props.getMaxToolCalls());
        this.maxEstimatedTokens = Math.max(1_000L, props.getMaxEstimatedTokens());
        this.signalStore = Objects.requireNonNullElse(signalStore, Optional.empty());
        this.tenantTokenQuota = Objects.requireNonNullElse(tenantTokenQuota, Optional.empty());
    }

    public Lease start(String sessionId) {
        return start(sessionId, null);
    }

    public Lease start(String sessionId, Long tenantId) {
        String key = key(sessionId);
        long deadline = System.currentTimeMillis() + deadlineMillis;
        Lease lease = new Lease(deadline, maxToolCalls, maxEstimatedTokens, tenantId);
        if (tenantId != null && tenantTokenQuota.isPresent()
                && !tenantTokenQuota.get().check(tenantId).allowed()) {
            lease.tenantQuotaDenied.set(true);
        }
        leases.put(key, lease);
        try {
            signalStore.ifPresent(store -> store.start(key, deadline, signalTtlMillis()));
        } catch (RuntimeException e) {
            leases.remove(key, lease);
            throw e;
        }
        return lease;
    }

    public void finish(String sessionId) {
        String key = key(sessionId);
        leases.remove(key);
        signalStore.ifPresent(store -> store.finish(key));
    }

    public void cancel(String sessionId) {
        String key = key(sessionId);
        leases.computeIfAbsent(key, ignored -> fallbackLease()).cancelled.set(true);
        signalStore.ifPresent(store -> store.cancel(key, signalTtlMillis()));
    }

    public boolean isCancelled(String sessionId) {
        Lease lease = lease(sessionId);
        return lease.cancelled.get() || signalStore.map(store -> store.isCancelled(key(sessionId))).orElse(false);
    }

    public void heartbeat(String sessionId) {
        long now = System.currentTimeMillis();
        lease(sessionId).heartbeatEpochMillis.set(now);
        signalStore.ifPresent(store -> store.heartbeat(key(sessionId), now, signalTtlMillis()));
    }
    public long heartbeatEpochMillis(String sessionId) { return lease(sessionId).heartbeatEpochMillis.get(); }

    public StopReason beforeTool(String sessionId) {
        Lease lease = lease(sessionId);
        heartbeat(sessionId);
        if (isCancelled(sessionId)) {
            return StopReason.CANCELLED;
        }
        if (System.currentTimeMillis() > lease.deadlineEpochMillis) {
            return StopReason.DEADLINE_EXCEEDED;
        }
        return lease.toolCalls.incrementAndGet() > lease.maxToolCalls ? StopReason.TOOL_BUDGET_EXCEEDED : StopReason.NONE;
    }

    public StopReason afterModelTokens(String sessionId, long estimated) {
        Lease lease = lease(sessionId);
        heartbeat(sessionId);
        if (isCancelled(sessionId)) {
            return StopReason.CANCELLED;
        }
        if (System.currentTimeMillis() > lease.deadlineEpochMillis) {
            return StopReason.DEADLINE_EXCEEDED;
        }
        if (lease.tenantQuotaDenied.get()) {
            return StopReason.TENANT_QUOTA_EXCEEDED;
        }
        long tokens = Math.max(0, estimated);
        if (lease.tenantId != null && tenantTokenQuota.isPresent()
                && !tenantTokenQuota.get().consume(lease.tenantId, tokens).allowed()) {
            lease.tenantQuotaDenied.set(true);
            return StopReason.TENANT_QUOTA_EXCEEDED;
        }
        return lease.estimatedTokens.addAndGet(tokens) > lease.maxEstimatedTokens
                ? StopReason.TOKEN_BUDGET_EXCEEDED : StopReason.NONE;
    }

    private Lease lease(String sessionId) { return leases.computeIfAbsent(key(sessionId), ignored -> fallbackLease()); }
    private Lease fallbackLease() {
        return new Lease(System.currentTimeMillis() + deadlineMillis,
                maxToolCalls, maxEstimatedTokens, null);
    }
    private long signalTtlMillis() { return Math.max(deadlineMillis + 60_000L, 120_000L); }
    private static String key(String sessionId) { return sessionId == null || sessionId.isBlank() ? "anonymous" : sessionId; }
}
