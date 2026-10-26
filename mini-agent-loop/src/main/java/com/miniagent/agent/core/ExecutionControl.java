package com.miniagent.agent.core;

import com.miniagent.common.event.SessionEvictedEvent;
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

/** 会话执行控制面：取消、deadline、心跳和资源预算都在这里收敛。 */
@Component
@EnableConfigurationProperties(ExecutionProperties.class)
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
        private final AtomicBoolean cancelled = new AtomicBoolean();
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
            this.deadlineEpochMillis = deadlineEpochMillis;
            this.maxToolCalls = maxToolCalls;
            this.tokenSoftLimit = tokenSoftLimit;
            this.tenantId = tenantId;
        }
    }

    private final Map<String, Lease> leases = new ConcurrentHashMap<>();
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

    public void finish(String sessionId) {
        String key = key(sessionId);
        leases.remove(key);
        signalStore.ifPresent(store -> store.finish(key));
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
        leases.remove(key);
        childToParent.remove(key);
        childToParent.entrySet().removeIf(e -> key.equals(e.getValue()));
        if (dropSignals) {
            signalStore.ifPresent(store -> store.finish(key));
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
        String key = key(sessionId);
        leases.computeIfAbsent(key, ignored -> fallbackLease()).cancelled.set(true);
        signalStore.ifPresent(store -> store.cancel(key, signalTtlMillis()));
    }

    public boolean isCancelled(String sessionId) {
        String resolved = resolve(key(sessionId));
        Lease lease = lease(sessionId);
        return lease.cancelled.get()
                || signalStore.map(store -> store.isCancelled(resolved)).orElse(false);
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
        lease(sessionId).heartbeatEpochMillis.set(now);
        signalStore.ifPresent(store -> store.heartbeat(resolved, now, signalTtlMillis()));
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
        // 累计 token 只统计、不判定。越过 tokenSoftLimit 时由调用方记一条告警并继续，
        // 不在这里返回停止原因 —— 详见 StopReason 的类注释。
        lease.estimatedTokens.addAndGet(tokens);
        return StopReason.NONE;
    }

    private Lease lease(String sessionId) {
        return leases.computeIfAbsent(resolve(key(sessionId)), ignored -> fallbackLease());
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
