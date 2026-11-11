package com.miniagent.agent.core;

import com.miniagent.common.event.SessionEvictedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** 会话执行控制面：取消、deadline、心跳和资源预算都在这里收敛。 */
@Component
@EnableConfigurationProperties(ExecutionProperties.class)
@Slf4j
public class ExecutionControl {
    /**
     * 停止原因。
     *
     * <p>这里<b>没有</b>「累计 token 耗尽」这一项，是刻意的：累计 token 度量的是
     * 「这个任务花了多少」，它随任务时长线性增长，与「任务是否失控」没有因果关系。
     * 一个正常的 60 轮任务和一次原地打转的死循环在这条线上长得一样，拿它做终止判据
     * 必然误杀长时间任务。失控由 {@code deadline}（墙钟）、{@code maxToolCalls}（工具次数）、
     * 循环轮次上限和重复工具检测拦截，这四道与工作量无关。</p>
     *
     * <p>累计 token 仍然统计（{@link #estimatedTokens}），但只用于展示与
     * {@code tokenSoftLimit} 告警 —— 超了只记一条轨迹，不结束任务。</p>
     */
    public enum StopReason {
        NONE,
        CANCELLED,
        DEADLINE_EXCEEDED,
        TOOL_BUDGET_EXCEEDED,
        TENANT_QUOTA_EXCEEDED
    }

    public static final class Lease {
        private final long deadlineEpochMillis;
        private final int maxToolCalls;
        /** 累计 token 的告警线，不是终止线。 */
        private final long tokenSoftLimit;
        private final Long tenantId;
        private final String signalId;
        private final String runToken;
        private boolean started;
        private final AtomicBoolean cancelled;
        private final AtomicBoolean pauseRequested;
        private final AtomicBoolean pausedAtCheckpoint = new AtomicBoolean();
        private final AtomicBoolean humanWaitpoint = new AtomicBoolean();
        private final AtomicBoolean tenantQuotaDenied = new AtomicBoolean();
        private final AtomicInteger toolCalls = new AtomicInteger();
        private final AtomicLong estimatedTokens = new AtomicLong();
        /**
         * 最近一次 LLM 响应的 {@code inputTokenCount} 快照（每次覆盖，不累加）。
         *
         * <p>压缩判据要的是「当前上下文占了多大」，而不是「一共花了多少」。
         * 本地 {@code TokenEstimator} 按字符加权估算，与厂商分词存在偏差；
         * 用这个真实值校准后，压缩触发点才不会系统性偏晚或偏早。</p>
         */
        private final AtomicLong lastInputTokens = new AtomicLong();
        /** 累计 token 越过告警线后只提示一次，避免每轮刷日志。 */
        private final AtomicBoolean tokenSoftLimitWarned = new AtomicBoolean();
        private final AtomicLong heartbeatEpochMillis = new AtomicLong(System.currentTimeMillis());

        private Lease(long deadlineEpochMillis, int maxToolCalls, long tokenSoftLimit,
                      Long tenantId) {
            this(deadlineEpochMillis, maxToolCalls, tokenSoftLimit, tenantId, null, null, null);
        }

        private Lease(long deadlineEpochMillis, int maxToolCalls, long tokenSoftLimit,
                      Long tenantId, String signalId, String runToken, Lease pending) {
            this.deadlineEpochMillis = deadlineEpochMillis;
            this.maxToolCalls = maxToolCalls;
            this.tokenSoftLimit = tokenSoftLimit;
            this.tenantId = tenantId;
            this.signalId = signalId;
            this.runToken = runToken;
            this.cancelled = pending == null ? new AtomicBoolean() : pending.cancelled;
            this.pauseRequested = pending == null ? new AtomicBoolean() : pending.pauseRequested;
        }
    }

    private final Map<String, Lease> leases = new ConcurrentHashMap<>();
    /** Also holds controls received after admission but before the worker starts. */
    private final Map<String, Lease> runLeases = new ConcurrentHashMap<>();
    /** 子会话查预算、取消时用父会话那一份租约。 */
    private final Map<String, String> childToParent = new ConcurrentHashMap<>();
    private final long deadlineMillis;
    private final int maxToolCalls;
    private final long tokenSoftLimit;
    private final Optional<ExecutionSignalStore> signalStore;
    private final Optional<TenantTokenQuota> tenantTokenQuota;

    public ExecutionControl(long deadlineMillis, int maxToolCalls, long tokenSoftLimit) {
        this(new ExecutionProperties(deadlineMillis, maxToolCalls, tokenSoftLimit),
                Optional.empty(), Optional.empty());
    }

    public ExecutionControl(long deadlineMillis, int maxToolCalls, long tokenSoftLimit,
                            Optional<ExecutionSignalStore> signalStore,
                            Optional<TenantTokenQuota> tenantTokenQuota) {
        this(new ExecutionProperties(deadlineMillis, maxToolCalls, tokenSoftLimit),
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
        this.tokenSoftLimit = Math.max(1_000L, props.getTokenSoftLimit());
        this.signalStore = Objects.requireNonNullElse(signalStore, Optional.empty());
        this.tenantTokenQuota = Objects.requireNonNullElse(tenantTokenQuota, Optional.empty());
    }

    /** 子 agent 与父会话共用取消、墙钟、工具次数和累计 token 计数。 */
    public void attach(String childSessionId, String parentSessionId) {
        if (childSessionId == null || childSessionId.isBlank()
                || parentSessionId == null || parentSessionId.isBlank()) {
            return;
        }
        childToParent.put(key(childSessionId), key(parentSessionId));
    }

    public void detach(String childSessionId) {
        if (childSessionId == null || childSessionId.isBlank()) {
            return;
        }
        childToParent.remove(key(childSessionId));
    }

    public Lease start(String sessionId) {
        return start(sessionId, null);
    }

    public Lease start(String sessionId, Long tenantId) {
        String key = key(sessionId);
        long deadline = System.currentTimeMillis() + deadlineMillis;
        Lease lease = new Lease(deadline, maxToolCalls, tokenSoftLimit, tenantId);
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

    public Lease start(String sessionId, Long tenantId, String runToken) {
        String sid = key(sessionId);
        String scoped = scopedKey(sid, runToken);
        prunePendingRuns();
        Lease lease = runLeases.compute(scoped, (ignored, pending) -> {
            if (pending != null && pending.started) return pending;
            Lease active = new Lease(System.currentTimeMillis() + deadlineMillis,
                    maxToolCalls, tokenSoftLimit, tenantId, scoped, runToken, pending);
            if (tenantId != null && tenantTokenQuota.isPresent()
                    && !tenantTokenQuota.get().check(tenantId).allowed()) {
                active.tenantQuotaDenied.set(true);
            }
            // Same scoped key is serialized with pre-start cancel/pause/resume.
            signalStore.ifPresent(store -> store.startPreservingControls(
                    scoped, active.deadlineEpochMillis, signalTtlMillis()));
            active.started = true;
            leases.put(sid, active);
            return active;
        });
        return lease;
    }

    public void finish(String sessionId, String runToken) {
        String scoped = scopedKey(key(sessionId), runToken);
        runLeases.compute(scoped, (ignored, lease) -> {
            if (lease != null) leases.remove(key(sessionId), lease);
            // Remote signals expire on their own. A Redis failure must not keep
            // local state alive or interrupt the caller's remaining cleanup.
            try {
                signalStore.ifPresent(store -> store.finish(scoped));
            } catch (RuntimeException e) {
                log.warn("Failed to clear execution signal for completed run: session={}, runToken={}",
                        key(sessionId), runToken, e);
            }
            return null;
        });
    }

    public void finish(String sessionId) {
        String key = key(sessionId);
        Lease lease = leases.remove(key);
        if (lease != null && lease.signalId != null) runLeases.remove(lease.signalId, lease);
        signalStore.ifPresent(store -> store.finish(signalId(key, lease)));
    }

    /**
     * 回收一个会话的执行面状态：本地租约、子会话映射，以及（删除场景下）跨进程信号。
     *
     * <p>{@code childToParent} 是双向可达的：既要删「以它为子」的条目，也要删
     * 「以它为父」的条目 —— 只删一边，子会话取消时仍会找到一条指向已删父会话的悬空记录。</p>
     */
    public void evict(String sessionId, boolean dropSignals) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        String key = key(sessionId);
        Lease lease = leases.remove(key);
        runLeases.entrySet().removeIf(e -> e.getKey().startsWith(scopedPrefix(key)));
        childToParent.remove(key);
        childToParent.entrySet().removeIf(e -> key.equals(e.getValue()));
        if (dropSignals) {
            signalStore.ifPresent(store -> store.finish(signalId(key, lease)));
        }
    }

    /**
     * 会话被删除或判定空闲时回收执行面状态。
     *
     * <p>删除是不可逆的，连跨进程信号一起清；空闲只丢掉进程内的租约与子会话映射 ——
     * 信号可能仍属于另一个副本上正在跑的一轮，不该由本副本替它判定结束。</p>
     */
    @EventListener
    public void onSessionEvicted(SessionEvictedEvent event) {
        evict(event.sessionId(), event.isDeleted());
    }

    public void cancel(String sessionId) {
        String resolved = resolve(key(sessionId));
        Lease lease = lease(sessionId);
        lease.cancelled.set(true);
        signalStore.ifPresent(store -> store.cancel(signalId(resolved, lease), signalTtlMillis()));
    }

    public void cancel(String sessionId, String runToken) {
        controlRun(sessionId, runToken, (scoped, lease) -> {
            lease.cancelled.set(true);
            signalStore.ifPresent(store -> store.cancel(scoped, signalTtlMillis()));
        });
    }

    public boolean isCancelled(String sessionId, String runToken) {
        String scoped = scopedKey(key(sessionId), runToken);
        Lease lease = runLeases.get(scoped);
        return (lease != null && lease.cancelled.get())
                || signalStore.map(store -> store.isCancelled(scoped)).orElse(false);
    }

    /**
     * Read the current run's stop state without waiting at a pause checkpoint.
     * Blocking operations (such as tool handlers) use this to observe cancellation
     * and the overall run deadline while preserving the normal pause semantics.
     */
    public StopReason stopReason(String sessionId) {
        Lease lease = lease(sessionId);
        if (isCancelled(sessionId)) return StopReason.CANCELLED;
        if (System.currentTimeMillis() > lease.deadlineEpochMillis) {
            return StopReason.DEADLINE_EXCEEDED;
        }
        return StopReason.NONE;
    }

    public void pause(String sessionId, String runToken) {
        controlRun(sessionId, runToken, (scoped, lease) -> {
            lease.pauseRequested.set(true);
            signalStore.ifPresent(store -> store.pause(scoped, signalTtlMillis()));
            if (lease.humanWaitpoint.get()
                    || signalStore.map(store -> store.isAtHumanWaitpoint(scoped)).orElse(false)) {
                lease.pausedAtCheckpoint.set(true);
                signalStore.ifPresent(store -> store.markPausedAtCheckpoint(scoped, true, signalTtlMillis()));
            }
        });
    }

    public void resume(String sessionId, String runToken) {
        controlRun(sessionId, runToken, (scoped, lease) -> {
            lease.pauseRequested.set(false);
            lease.pausedAtCheckpoint.set(false);
            signalStore.ifPresent(store -> store.resume(scoped, signalTtlMillis()));
        });
    }

    public boolean isPauseRequested(String sessionId, String runToken) {
        String scoped = scopedKey(key(sessionId), runToken);
        Lease lease = runLeases.get(scoped);
        return signalStore.map(store -> store.isPaused(scoped))
                .orElseGet(() -> lease != null && lease.pauseRequested.get());
    }

    public boolean isPausedAtCheckpoint(String sessionId, String runToken) {
        if (!isPauseRequested(sessionId, runToken)) return false;
        String scoped = scopedKey(key(sessionId), runToken);
        Lease lease = runLeases.get(scoped);
        return signalStore.map(store -> store.isPausedAtCheckpoint(scoped))
                .orElseGet(() -> lease != null && lease.pausedAtCheckpoint.get());
    }

    private void controlRun(String sessionId, String runToken,
                            java.util.function.BiConsumer<String, Lease> control) {
        String scoped = scopedKey(key(sessionId), runToken);
        prunePendingRuns();
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        runLeases.compute(scoped, (ignored, previous) -> {
            Lease lease = previous == null ? new Lease(System.currentTimeMillis() + deadlineMillis,
                    maxToolCalls, tokenSoftLimit, null, scoped, runToken, null) : previous;
            // Install the local control first. A remote signal-store failure must
            // not discard a pre-start cancel/pause/resume when compute returns.
            try {
                control.accept(scoped, lease);
            } catch (RuntimeException e) {
                failure.set(e);
            }
            return lease;
        });
        // The API must still report a failed cross-replica write, but rethrow
        // only after compute committed the local pre-start control.
        if (failure.get() != null) throw failure.get();
    }

    private void prunePendingRuns() {
        long now = System.currentTimeMillis();
        runLeases.entrySet().removeIf(e -> now > e.getValue().deadlineEpochMillis + 60_000L
                && !leases.containsValue(e.getValue()));
    }

    /** 请求在下一个安全检查点暂停；正在运行的外部工具会先完成当前调用。 */
    public void pause(String sessionId) {
        String resolved = resolve(key(sessionId));
        Lease lease = lease(sessionId);
        lease.pauseRequested.set(true);
        signalStore.ifPresent(store -> store.pause(signalId(resolved, lease), signalTtlMillis()));
        if (isAtHumanWaitpoint(sessionId)) {
            lease.pausedAtCheckpoint.set(true);
            signalStore.ifPresent(store -> store.markPausedAtCheckpoint(
                    signalId(resolved, lease), true, signalTtlMillis()));
        }
    }

    /** 清除暂停请求；阻塞在检查点的执行线程会继续。 */
    public void resume(String sessionId) {
        String resolved = resolve(key(sessionId));
        Lease lease = lease(sessionId);
        lease.pauseRequested.set(false);
        lease.pausedAtCheckpoint.set(false);
        signalStore.ifPresent(store -> store.resume(signalId(resolved, lease), signalTtlMillis()));
    }

    public boolean isPauseRequested(String sessionId) {
        String resolved = resolve(key(sessionId));
        Lease lease = lease(sessionId);
        return signalStore.map(store -> store.isPaused(signalId(resolved, lease)))
                .orElseGet(lease.pauseRequested::get);
    }

    public boolean isPausedAtCheckpoint(String sessionId) {
        String resolved = resolve(key(sessionId));
        Lease lease = lease(sessionId);
        if (!isPauseRequested(sessionId)) return false;
        return signalStore.map(store -> store.isPausedAtCheckpoint(signalId(resolved, lease)))
                .orElseGet(lease.pausedAtCheckpoint::get);
    }

    /** 标记当前执行线程正在等待用户审批；该等待本身是可暂停安全点。 */
    public void markHumanWaitpoint(String sessionId, boolean waiting) {
        String resolved = resolve(key(sessionId));
        Lease lease = lease(sessionId);
        lease.humanWaitpoint.set(waiting);
        signalStore.ifPresent(store -> store.markHumanWaitpoint(
                signalId(resolved, lease), waiting, signalTtlMillis()));
    }

    public boolean isAtHumanWaitpoint(String sessionId) {
        String resolved = resolve(key(sessionId));
        Lease lease = lease(sessionId);
        return signalStore.map(store -> store.isAtHumanWaitpoint(signalId(resolved, lease)))
                .orElseGet(lease.humanWaitpoint::get);
    }

    /**
     * 阻塞在可中断的执行边界，直到用户恢复、取消或任务超时。
     * Redis 状态以短间隔轮询，允许 API 请求由另一应用副本接收。
     */
    public StopReason awaitIfPaused(String sessionId) {
        String resolved = resolve(key(sessionId));
        Lease lease = lease(sessionId);
        boolean markedPaused = false;
        try {
            while (isPauseRequested(sessionId)) {
                if (isCancelled(sessionId)) return StopReason.CANCELLED;
                if (System.currentTimeMillis() > lease.deadlineEpochMillis) {
                    return StopReason.DEADLINE_EXCEEDED;
                }
                if (!markedPaused) {
                    lease.pausedAtCheckpoint.set(true);
                    signalStore.ifPresent(store -> store.markPausedAtCheckpoint(
                            signalId(resolved, lease), true, signalTtlMillis()));
                    markedPaused = true;
                }
                Thread.sleep(200L);
                heartbeat(sessionId);
            }
            return isCancelled(sessionId) ? StopReason.CANCELLED : StopReason.NONE;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return StopReason.CANCELLED;
        } finally {
            if (markedPaused) {
                lease.pausedAtCheckpoint.set(false);
                signalStore.ifPresent(store -> store.markPausedAtCheckpoint(
                        signalId(resolved, lease), false, signalTtlMillis()));
            }
        }
    }

    public boolean isCancelled(String sessionId) {
        String resolved = resolve(key(sessionId));
        Lease lease = lease(sessionId);
        return lease.cancelled.get()
                || signalStore.map(store -> store.isCancelled(signalId(resolved, lease))).orElse(false);
    }

    /**
     * 当前任务已累计的估算 token（input+output 累加）。
     *
     * <p><b>只是统计值</b>：用于轨迹展示与 {@link #tokenSoftLimitExceeded} 告警，
     * 不参与任何停止判定。想知道「当前上下文多大」请用 {@link #lastInputTokens}。</p>
     */
    public long estimatedTokens(String sessionId) {
        return lease(sessionId).estimatedTokens.get();
    }

    /**
     * 最近一次 LLM 调用的 {@code inputTokenCount} 快照（每次覆盖，不累加）。
     *
     * <p>压缩判据要区分两个量：{@code estimatedTokens} 回答「一共花了多少」，
     * 这个方法回答「现在这一次请求多大」（含全部历史与工具 schema）。
     * 只有后者能回答「上下文还剩多少空间」，也才能和窗口大小相除得到占比。</p>
     */
    public long lastInputTokens(String sessionId) {
        return lease(sessionId).lastInputTokens.get();
    }

    /** 记录最近一次 input 快照。传 0 视为「未知」，保留上一次的值。 */
    public void recordInputTokens(String sessionId, long inputTokens) {
        if (inputTokens <= 0) {
            return;
        }
        lease(sessionId).lastInputTokens.set(inputTokens);
    }

    /**
     * 累计 token 是否已越过告警线。
     *
     * <p>越过只意味着「这个任务花得不少了」，<b>不构成停止理由</b>。
     * 调用方应把它变成一条轨迹 + 一条日志，然后继续跑完任务。</p>
     */
    public boolean tokenSoftLimitExceeded(String sessionId) {
        Lease lease = lease(sessionId);
        return lease.estimatedTokens.get() > lease.tokenSoftLimit;
    }

    /**
     * 越过告警线且是首次时返回 true，其余情况一律 false。
     *
     * <p>越线判断放在方法内部而不是交给调用方：<b>一个方法回答一个完整问题</b>
     * ——「现在该不该发这条告警」。否则调用方漏判一次，就会在没越线时发告警，
     * 或者把去重标记提前置位、让真正的越线反而报不出来。</p>
     *
     * <p>去重标记放在 Lease 上而不是调用方局部变量：主循环每轮都会检查，
     * 不去重会刷满日志；而子代理共享父 lease，放在调用方又会让同一个告警提示 N 次。</p>
     */
    public boolean markTokenSoftLimitWarned(String sessionId) {
        Lease lease = lease(sessionId);
        if (lease.estimatedTokens.get() <= lease.tokenSoftLimit) {
            return false;
        }
        return lease.tokenSoftLimitWarned.compareAndSet(false, true);
    }

    /** 累计 token 告警线的值，供日志与提示文案使用。 */
    public long tokenSoftLimit() {
        return tokenSoftLimit;
    }

    public void heartbeat(String sessionId) {
        String resolved = resolve(key(sessionId));
        long now = System.currentTimeMillis();
        Lease lease = lease(sessionId);
        lease.heartbeatEpochMillis.set(now);
        signalStore.ifPresent(store -> store.heartbeat(signalId(resolved, lease), now, signalTtlMillis()));
    }
    public long heartbeatEpochMillis(String sessionId) { return lease(sessionId).heartbeatEpochMillis.get(); }

    public StopReason beforeTool(String sessionId) {
        Lease lease = lease(sessionId);
        StopReason checkpoint = checkpoint(sessionId);
        if (checkpoint != StopReason.NONE) return checkpoint;
        return lease.toolCalls.incrementAndGet() > lease.maxToolCalls ? StopReason.TOOL_BUDGET_EXCEEDED : StopReason.NONE;
    }

    /** 可重入的暂停/取消/超时安全边界，不会消耗工具调用预算。 */
    public StopReason checkpoint(String sessionId) {
        Lease lease = lease(sessionId);
        StopReason pauseStop = awaitIfPaused(sessionId);
        if (pauseStop != StopReason.NONE) return pauseStop;
        heartbeat(sessionId);
        if (isCancelled(sessionId)) return StopReason.CANCELLED;
        if (System.currentTimeMillis() > lease.deadlineEpochMillis) return StopReason.DEADLINE_EXCEEDED;
        return StopReason.NONE;
    }

    public StopReason afterModelTokens(String sessionId, long estimated) {
        Lease lease = lease(sessionId);
        StopReason checkpoint = checkpoint(sessionId);
        if (checkpoint != StopReason.NONE) return checkpoint;
        if (lease.tenantQuotaDenied.get()) {
            return StopReason.TENANT_QUOTA_EXCEEDED;
        }
        long tokens = Math.max(0, estimated);
        if (lease.tenantId != null && tenantTokenQuota.isPresent()
                && !tenantTokenQuota.get().consume(lease.tenantId, tokens).allowed()) {
            lease.tenantQuotaDenied.set(true);
            return StopReason.TENANT_QUOTA_EXCEEDED;
        }
        // 累计 token 只统计、不判定。越过 tokenSoftLimit 时由调用方记一条告警并继续，
        // 不在这里返回停止原因 —— 详见 StopReason 的类注释。
        lease.estimatedTokens.addAndGet(tokens);
        return StopReason.NONE;
    }

    private Lease lease(String sessionId) {
        String resolved = resolve(key(sessionId));
        String token = TaskExecutionContext.currentRunToken();
        if (token != null && !token.isBlank()) {
            Lease scoped = runLeases.get(scopedKey(resolved, token));
            Lease current = leases.get(resolved);
            if (scoped != null && (current == null || token.equals(current.runToken))) return scoped;
            // A finished worker must never adopt the next run's budget or controls.
            Lease stale = new Lease(System.currentTimeMillis() + deadlineMillis,
                    maxToolCalls, tokenSoftLimit, null, scopedKey(resolved, token), token, null);
            stale.cancelled.set(true);
            return stale;
        }
        return leases.computeIfAbsent(resolved, ignored -> fallbackLease());
    }

    private static String signalId(String sessionId, Lease lease) {
        return lease == null || lease.signalId == null ? sessionId : lease.signalId;
    }

    private static String scopedPrefix(String sessionId) {
        return "run:" + sessionId.length() + ":" + sessionId + ":";
    }

    private static String scopedKey(String sessionId, String runToken) {
        if (runToken == null || runToken.isBlank()) throw new IllegalArgumentException("runToken required");
        return scopedPrefix(sessionId) + runToken;
    }

    private String resolve(String key) {
        String parent = childToParent.get(key);
        return parent == null ? key : parent;
    }
    private Lease fallbackLease() {
        return new Lease(System.currentTimeMillis() + deadlineMillis,
                maxToolCalls, tokenSoftLimit, null);
    }
    private long signalTtlMillis() { return Math.max(deadlineMillis + 60_000L, 120_000L); }
    private static String key(String sessionId) { return sessionId == null || sessionId.isBlank() ? "anonymous" : sessionId; }
}
