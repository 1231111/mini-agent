package com.miniagent.agent.permission;

import com.miniagent.common.event.SessionEvictedEvent;
import com.miniagent.common.permission.ExecPolicy;
import jakarta.annotation.PostConstruct;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.UUID;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

/**
 * 按 sessionId 保存权限模式、Plan 批准状态、带参数摘要和过期时间的 Ask 授权、待办确认策略，
 * 以及 exec_command 策略的会话级覆盖。
 */
@Component
public class SessionPermissionStore {

    /**
     * @param execPolicyOverride exec_command 策略的会话级覆盖。
     *        {@code null} 表示"跟随全局默认"（{@code agent.tools.exec-policy}）——
     *        刻意用 null 而不是预先填一个具体档位，
     *        否则全局默认改了、已有会话不会跟着变，排查起来很容易误判成"配置没生效"。
     */
    public record SessionPerm(
            PermissionMode mode,
            boolean planApproved,
            Set<String> askGrantedTools,
            Map<String, AskGrant> askGrants,
            ConfirmPolicy confirmPolicy,
            ExecPolicy execPolicyOverride
    ) {
        /** Compatibility constructor for callers compiled against the old set-only state. */
        public SessionPerm(
                PermissionMode mode,
                boolean planApproved,
                Set<String> askGrantedTools,
                ConfirmPolicy confirmPolicy,
                ExecPolicy execPolicyOverride) {
            this(mode, planApproved, askGrantedTools, Map.of(), confirmPolicy,
                    execPolicyOverride);
        }

        public SessionPerm {
            askGrantedTools = askGrantedTools == null
                    ? Set.of()
                    : Set.copyOf(askGrantedTools);
            askGrants = askGrants == null ? Map.of() : Map.copyOf(askGrants);
        }
    }

    private record Cached(SessionPerm permission, long version) {
    }

    private static final int MAX_CAS_ATTEMPTS = 5;
    private static final int MAX_PENDING_ASK_GRANTS = 4096;
    private static final int MAX_APPROVED_ASK_GRANTS = 4096;
    private static final long LEGACY_GRANT_TTL_MILLIS =
            TimeUnit.MINUTES.toMillis(5);

    private final ConcurrentHashMap<String, Cached> bySession =
            new ConcurrentHashMap<>();
    /** Pending grants are deliberately ephemeral: a restart must require a new approval. */
    private final ConcurrentHashMap<String, AskGrant> pendingAskGrants =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ApprovedAsk> approvedAskWaiters =
            new ConcurrentHashMap<>();
    private final SessionPermissionPersistence persistence;

    public record ApprovedAsk(AskGrant grant, boolean resumeAttached, boolean approved) {
    }

    public SessionPermissionStore() {
        this(null);
    }

    @Autowired
    public SessionPermissionStore(
            @Autowired(required = false)
            SessionPermissionPersistence persistence) {
        this.persistence = persistence;
    }

    @PostConstruct
    void bindToContext() {
        PermissionContext.bindStore(this);
    }

    private static SessionPerm defaults() {
        return new SessionPerm(
                PermissionMode.DEFAULT, false,
                Set.of(), Map.of(), ConfirmPolicy.DANGEROUS, null);
    }

    public SessionPerm get(String sessionId) {
        if (StringUtils.isBlank(sessionId)) {
            return defaults();
        }
        if (persistence == null) {
            return bySession.computeIfAbsent(
                    sessionId, sid -> new Cached(defaults(), 0L))
                    .permission();
        }
        SessionPermissionPersistence.State state =
                persistence.loadOrCreate(sessionId);
        return cache(sessionId, state).permission();
    }

    private Cached cache(
            String sessionId, SessionPermissionPersistence.State state) {
        return bySession.compute(sessionId, (key, current) -> {
            if (current != null && current.version() == state.version()) {
                return current;
            }
            return new Cached(fromState(state), state.version());
        });
    }

    private SessionPerm fromState(SessionPermissionPersistence.State state) {
        ExecPolicy execPolicy = null;
        if (StringUtils.isNotBlank(state.execPolicyOverride())) {
            execPolicy = ExecPolicy.parse(state.execPolicyOverride());
            if (execPolicy == null) {
                throw new IllegalStateException(
                        "Invalid persisted exec policy: "
                                + state.execPolicyOverride());
            }
        }
        return new SessionPerm(
                PermissionMode.from(state.mode()),
                state.planApproved(),
                state.askGrantedTools(),
                state.askGrants(),
                ConfirmPolicy.from(state.confirmPolicy()),
                execPolicy);
    }

    private SessionPermissionPersistence.State toState(
            SessionPerm permission, long version) {
        return new SessionPermissionPersistence.State(
                permission.mode().wireName(),
                permission.planApproved(),
                permission.askGrantedTools(),
                permission.askGrants(),
                permission.confirmPolicy().wireName(),
                Optional.ofNullable(permission.execPolicyOverride())
                        .map(ExecPolicy::wireName)
                        .orElse(null),
                version);
    }

    private void update(
            String sessionId, UnaryOperator<SessionPerm> mutation) {
        if (persistence == null) {
            bySession.compute(sessionId, (key, current) -> {
                Cached base = current == null
                        ? new Cached(defaults(), 0L)
                        : current;
                SessionPerm next = mutation.apply(base.permission());
                return new Cached(next, base.version() + 1L);
            });
            return;
        }
        for (int attempt = 0; attempt < MAX_CAS_ATTEMPTS; attempt++) {
            SessionPermissionPersistence.State expected =
                    persistence.loadOrCreate(sessionId);
            SessionPerm nextPermission = mutation.apply(fromState(expected));
            SessionPermissionPersistence.State next =
                    toState(nextPermission, expected.version() + 1L);
            if (persistence.compareAndSet(sessionId, expected, next)) {
                cache(sessionId, next);
                return;
            }
        }
        throw new IllegalStateException(
                "Permission CAS conflict after " + MAX_CAS_ATTEMPTS
                        + " attempts for session " + sessionId);
    }

    public PermissionMode getMode(String sessionId) {
        return get(sessionId).mode();
    }

    public ConfirmPolicy getConfirmPolicy(String sessionId) {
        ConfirmPolicy p = get(sessionId).confirmPolicy();
        return p == null ? ConfirmPolicy.DANGEROUS : p;
    }

    public void setMode(String sessionId, PermissionMode mode) {
        if (StringUtils.isBlank(sessionId) || Objects.isNull(mode)) {
            return;
        }
        update(sessionId, old -> {
            Set<String> grants = old.askGrantedTools();
            boolean approved = mode == PermissionMode.PLAN
                    ? old.planApproved()
                    : true;
            if (mode == PermissionMode.PLAN
                    && old.mode() != PermissionMode.PLAN) {
                approved = false;
            }
            ConfirmPolicy policy = old.confirmPolicy() == null
                    ? ConfirmPolicy.DANGEROUS
                    : old.confirmPolicy();
            return new SessionPerm(
                    mode,
                    approved,
                    grants,
                    old.askGrants(),
                    policy,
                    old.execPolicyOverride());
        });
    }

    public void setConfirmPolicy(String sessionId, ConfirmPolicy policy) {
        if (StringUtils.isBlank(sessionId) || Objects.isNull(policy)) {
            return;
        }
        update(sessionId, base -> {
            return new SessionPerm(
                    base.mode(), base.planApproved(), base.askGrantedTools(),
                    base.askGrants(), policy,
                    base.execPolicyOverride());
        });
    }

    /**
     * 设置 exec_command 策略的会话级覆盖。传 {@code null} 表示清除覆盖、改为跟随全局默认。
     *
     * <p>与 {@link #setMode} 里的 planApproved 处理不同：切换策略时**不动** askGrantedTools。
     * 理由是收紧档位（ALLOW→ASK）时，之前批准过的命令不该被"记忆"成永久放行 ——
     * 所以这里顺手把 exec_command 的 grant 撤掉。
     */
    public void setExecPolicyOverride(String sessionId, ExecPolicy policy) {
        if (StringUtils.isBlank(sessionId)) {
            return;
        }
        update(sessionId, base -> {
            Set<String> grants = new HashSet<>(base.askGrantedTools());
            Map<String, AskGrant> askGrants = new HashMap<>(base.askGrants());
            if (policy != ExecPolicy.ALLOW) {
                // 收紧：丢掉旧的 exec 批准，逼它重新问一次
                grants.remove(PermissionPolicy.EXEC_TOOL);
                askGrants.values().removeIf(g -> PermissionPolicy.EXEC_TOOL.equals(g.toolName()));
            }
            return new SessionPerm(
                    base.mode(), base.planApproved(), grants, askGrants,
                    base.confirmPolicy(), policy);
        });
    }

    /** 会话级覆盖；{@code null} 表示跟随全局默认。 */
    public ExecPolicy getExecPolicyOverride(String sessionId) {
        if (StringUtils.isBlank(sessionId)) {
            return null;
        }
        return get(sessionId).execPolicyOverride();
    }

    /** 用户批准 Plan：保持 plan 模式但打开放行写工具；或切回 default */
    public void approvePlan(String sessionId) {
        if (StringUtils.isBlank(sessionId)) {
            return;
        }
        update(sessionId, old -> {
            PermissionMode mode = old.mode();
            Set<String> grants = old.askGrantedTools();
            ConfirmPolicy policy = old.confirmPolicy() == null
                    ? ConfirmPolicy.DANGEROUS
                    : old.confirmPolicy();
            if (mode != PermissionMode.PLAN) {
                mode = PermissionMode.DEFAULT;
            }
            return new SessionPerm(
                    mode,
                    true,
                    grants,
                    old.askGrants(),
                    policy,
                    old.execPolicyOverride());
        });
    }

    public boolean isPlanApproved(String sessionId) {
        SessionPerm p = get(sessionId);
        if (p.mode() != PermissionMode.PLAN) {
            return true;
        }
        return p.planApproved();
    }

    public void grantAskTool(String sessionId, String toolName) {
        if (Objects.isNull(sessionId) || StringUtils.isBlank(toolName)) {
            return;
        }
        // Legacy callers do not provide an approval token or arguments. Keep
        // the old method source-compatible, but bind it to the only legacy
        // argument shape ({}) and a short TTL. The execution pipeline never
        // treats the old tool-name set as an authorization.
        String token = newToken();
        AskGrant grant = new AskGrant(token, toolName.trim(),
                argumentDigest(toolName.trim(), "{}"),
                System.currentTimeMillis() + LEGACY_GRANT_TTL_MILLIS);
        update(sessionId, old -> {
            Set<String> grants = new HashSet<>(old.askGrantedTools());
            grants.add(toolName.trim());
            Map<String, AskGrant> askGrants = new HashMap<>(old.askGrants());
            askGrants.put(token, grant);
            return new SessionPerm(
                    old.mode(),
                    old.planApproved(),
                    grants,
                    askGrants,
                    old.confirmPolicy(),
                    old.execPolicyOverride());
        });
    }

    /**
     * Issues an opaque, short-lived token for the exact invocation shown to
     * the user. It remains pending until {@link #grantAskToken} is called.
     *
     * <p>Pending token 和它的 waiter 状态必须在同一把锁里建立。拆成两次独立 put 的话，
     * 执行线程可能正好落在两次 put 中间：{@code attachAskResume} 看不到 waiter，
     * 这次调用就被静默降级成「不等待」，用户点批准时也不存在可唤醒的调用点 ——
     * 表现正是「已经批了还在弹」。所以这里从查重、清理、限容到写入全部收进
     * {@code synchronized (approvedAskWaiters)}。</p>
     */
    public AskGrant issueAskGrant(String sessionId, String toolName,
                                  String arguments, long ttlMillis) {
        if (StringUtils.isBlank(sessionId) || StringUtils.isBlank(toolName)) {
            return null;
        }
        String tool = toolName.trim();
        String digest = argumentDigest(tool, arguments);
        long now = System.currentTimeMillis();
        String prefix = sessionId + "\u0000";
        synchronized (approvedAskWaiters) {
            // Reuse a still-pending approval for the same exact invocation. This
            // avoids an unbounded token stream while an SSE client is reconnecting.
            for (Map.Entry<String, AskGrant> entry : pendingAskGrants.entrySet()) {
                AskGrant existing = entry.getValue();
                if (entry.getKey().startsWith(prefix)
                        && !existing.expired(now)
                        && existing.toolName().equals(tool)
                        && existing.argumentsDigest().equals(digest)) {
                    return existing;
                }
            }
            purgeExpiredLocked(now);
            if (pendingAskGrants.size() >= MAX_PENDING_ASK_GRANTS) {
                // Capacity is a safety bound, not an authorization decision. Drop
                // one pending entry; the user can request it again and receive a
                // fresh token. 它的 waiter 必须一起丢：只丢 token 会让等待线程
                // 挂到 TTL 结束才醒，看起来像卡死。
                evictOnePendingLocked();
            }
            AskGrant grant = new AskGrant(
                    newToken(), tool, digest, now + Math.max(1_000L, ttlMillis));
            String key = pendingKey(sessionId, grant.token());
            approvedAskWaiters.put(key, new ApprovedAsk(grant, false, false));
            pendingAskGrants.put(key, grant);
            return grant;
        }
    }

    /** Caller must hold the {@code approvedAskWaiters} monitor. */
    private void purgeExpiredLocked(long now) {
        for (Map.Entry<String, AskGrant> entry : pendingAskGrants.entrySet()) {
            if (!entry.getValue().expired(now)) {
                continue;
            }
            if (pendingAskGrants.remove(entry.getKey(), entry.getValue())) {
                approvedAskWaiters.remove(entry.getKey());
            }
        }
        // 已批准但还没被等待线程取走的 waiter 不能因为 pending 已被移除就清掉 ——
        // grantAskToken(requireResume) 正是「先移 pending、再留 approved waiter」。
        // 这里只回收彻底过期的那些，它们本来也只会让等待方拿到 empty。
        approvedAskWaiters.entrySet().removeIf(
                e -> e.getValue().grant().expired(now));
    }

    /** Caller must hold the {@code approvedAskWaiters} monitor. */
    private void evictOnePendingLocked() {
        var iterator = pendingAskGrants.keySet().iterator();
        if (!iterator.hasNext()) {
            return;
        }
        String victim = iterator.next();
        AskGrant evicted = pendingAskGrants.remove(victim);
        if (evicted != null) {
            approvedAskWaiters.remove(victim);
            approvedAskWaiters.notifyAll();
        }
    }

    /**
     * Bind a live AgentLoop wait point to the exact single-use grant.
     *
     * <p>只有「仍未批准、未过期、尚未被别的线程绑定」的 pending token 才能绑定。
     * 重复绑定直接失败，避免同一个 token 唤醒两条工具调用。</p>
     */
    public boolean attachAskResume(String sessionId, String token) {
        if (StringUtils.isBlank(sessionId) || StringUtils.isBlank(token)) {
            return false;
        }
        String key = pendingKey(sessionId, token.trim());
        long now = System.currentTimeMillis();
        synchronized (approvedAskWaiters) {
            ApprovedAsk waiting = approvedAskWaiters.get(key);
            if (waiting == null) {
                return false;
            }
            if (waiting.grant().expired(now)) {
                pendingAskGrants.remove(key, waiting.grant());
                approvedAskWaiters.remove(key, waiting);
                approvedAskWaiters.notifyAll();
                return false;
            }
            // 已批准（pending 已被移走）或已被别的线程绑定：本次调用不参与等待，
            // 但绝不能清掉那条待批准状态 —— 那是另一次调用的、或即将被执行的状态。
            if (waiting.approved() || waiting.resumeAttached()
                    || !pendingAskGrants.containsKey(key)) {
                return false;
            }
            approvedAskWaiters.put(key, new ApprovedAsk(waiting.grant(), true, false));
            return true;
        }
    }

    /** 供管线自检/测试观察：该 token 当前是否有一条已绑定且未过期的等待调用。 */
    public boolean hasAttachedAskResume(String sessionId, String token) {
        if (StringUtils.isBlank(sessionId) || StringUtils.isBlank(token)) {
            return false;
        }
        ApprovedAsk waiting = approvedAskWaiters.get(pendingKey(sessionId, token.trim()));
        return waiting != null && waiting.resumeAttached()
                && !waiting.approved()
                && !waiting.grant().expired(System.currentTimeMillis());
    }

    /**
     * Wait for explicit approval; interruption is propagated so task cancellation remains effective.
     *
     * <p>返回 empty 的三种原因都必须是终态：等待窗口到期、token 过期、被拒绝/被清理。
     * 三种情况都要把 pending token 一起摘掉，否则 token 还能被后到的批准消费，
     * 于是用户在已经超时的弹窗上再点一次「批准」仍然返回成功。</p>
     */
    public Optional<AskGrant> awaitAskApproval(String sessionId, String token, long timeoutMillis)
            throws InterruptedException {
        if (StringUtils.isBlank(sessionId) || StringUtils.isBlank(token)) {
            return Optional.empty();
        }
        String key = pendingKey(sessionId, token.trim());
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(1L, timeoutMillis));
        synchronized (approvedAskWaiters) {
            while (true) {
                ApprovedAsk waiting = approvedAskWaiters.get(key);
                if (waiting == null) {
                    return Optional.empty();
                }
                long now = System.currentTimeMillis();
                if (waiting.grant().expired(now)) {
                    abandonLocked(key, waiting);
                    return Optional.empty();
                }
                if (waiting.approved()) {
                    approvedAskWaiters.remove(key, waiting);
                    return Optional.of(waiting.grant());
                }
                long remain = deadline - System.nanoTime();
                if (remain <= 0L) {
                    abandonLocked(key, waiting);
                    return Optional.empty();
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(approvedAskWaiters, remain);
                } catch (InterruptedException e) {
                    // 取消/中断也是终态：不清理的话，用户稍后批准会命中一条
                    // 已经没有线程在等的 waiter，看起来像「批了没反应」。
                    abandonLocked(key, waiting);
                    throw e;
                }
            }
        }
    }

    /** Caller must hold the {@code approvedAskWaiters} monitor. */
    private void abandonLocked(String key, ApprovedAsk waiting) {
        pendingAskGrants.remove(key, waiting.grant());
        approvedAskWaiters.remove(key, waiting);
        approvedAskWaiters.notifyAll();
    }

    /**
     * Rejects a pending invocation and wakes its live execution thread.
     *
     * <p>不要求 {@code resumeAttached}：没有等待线程时也要把 pending token 摘掉，
     * 否则这一次拒绝等于没拒绝 —— token 仍然可以被后到的批准消费。</p>
     */
    public boolean denyAskToken(String sessionId, String token, String expectedTool) {
        if (StringUtils.isBlank(sessionId) || StringUtils.isBlank(token)) {
            return false;
        }
        String key = pendingKey(sessionId, token.trim());
        long now = System.currentTimeMillis();
        synchronized (approvedAskWaiters) {
            AskGrant grant = pendingAskGrants.get(key);
            if (grant == null || grant.expired(now)) {
                pendingAskGrants.remove(key);
                approvedAskWaiters.remove(key);
                approvedAskWaiters.notifyAll();
                return false;
            }
            if (StringUtils.isNotBlank(expectedTool)
                    && !grant.toolName().equals(expectedTool.trim())) {
                return false;
            }
            if (!pendingAskGrants.remove(key, grant)) {
                return false;
            }
            approvedAskWaiters.remove(key);
            approvedAskWaiters.notifyAll();
            return true;
        }
    }

    /**
     * Converts a pending token into an approved exact-argument grant. A token
     * is single-use; expired or cross-session tokens are rejected.
     */
    public Optional<AskGrant> grantAskToken(String sessionId, String token) {
        return grantAskToken(sessionId, token, null, false);
    }

    /** Same as {@link #grantAskToken(String, String)} with an optional UI tool consistency check. */
    public Optional<AskGrant> grantAskToken(String sessionId, String token,
                                            String expectedTool) {
        return grantAskToken(sessionId, token, expectedTool, false);
    }

    /** Approves only a token that is still bound to a live execution waiting point. */
    public Optional<AskGrant> grantAskTokenForResume(String sessionId, String token,
                                                      String expectedTool) {
        return grantAskToken(sessionId, token, expectedTool, true);
    }

    /**
     * 把本会话里所有仍在等待的审批点一次性放行，返回放行条数。
     *
     * <p>给「任务跑到一半，用户把自动编辑和自动确认都打开了」用。已经弹给用户的
     * permission_ask 是一个**已经发起**的等待点，它不会因为后续步骤改成全自动而消失 ——
     * 那个工具线程还阻塞在 {@link #awaitAskApproval} 里，前端弹窗也还亮着。
     * 不在这里放行的话，用户看到的仍然是「明明开了全自动，还卡在授权弹窗上」。</p>
     *
     * <p>放行只影响这些**已经存在的**等待点：摘掉 pending token，把 waiter 标成已批准，
     * 由原线程继续执行；不写持久化 grant，也不影响后续调用 —— 后续调用由
     * {@code PermissionPolicy.isFullAuto} 直接判定免批，根本不会再走到这里。</p>
     */
    public int releasePendingAsks(String sessionId) {
        if (StringUtils.isBlank(sessionId)) {
            return 0;
        }
        String prefix = sessionId + "\u0000";
        long now = System.currentTimeMillis();
        int released = 0;
        synchronized (approvedAskWaiters) {
            for (Map.Entry<String, ApprovedAsk> entry
                    : new ArrayList<>(approvedAskWaiters.entrySet())) {
                String key = entry.getKey();
                if (!key.startsWith(prefix)) {
                    continue;
                }
                ApprovedAsk waiting = entry.getValue();
                if (waiting.approved()) {
                    continue;
                }
                if (waiting.grant().expired(now)) {
                    abandonLocked(key, waiting);
                    continue;
                }
                pendingAskGrants.remove(key, waiting.grant());
                approvedAskWaiters.put(key,
                        new ApprovedAsk(waiting.grant(), waiting.resumeAttached(), true));
                released++;
            }
            if (released > 0) {
                approvedAskWaiters.notifyAll();
            }
        }
        return released;
    }

    private Optional<AskGrant> grantAskToken(String sessionId, String token,
                                              String expectedTool, boolean requireResume) {
        if (StringUtils.isBlank(sessionId) || StringUtils.isBlank(token)) {
            return Optional.empty();
        }
        String key = pendingKey(sessionId, token.trim());
        AskGrant approved;
        boolean persist;
        synchronized (approvedAskWaiters) {
            AskGrant candidate = pendingAskGrants.get(key);
            ApprovedAsk waiter = approvedAskWaiters.get(key);
            long now = System.currentTimeMillis();
            if (candidate == null || candidate.expired(now)) {
                // 过期/已消费的 token 顺手清干净，避免 waiter 残留到 TTL 之后。
                if (candidate != null) {
                    pendingAskGrants.remove(key, candidate);
                    approvedAskWaiters.remove(key);
                    approvedAskWaiters.notifyAll();
                }
                return Optional.empty();
            }
            if (StringUtils.isNotBlank(expectedTool)
                    && !candidate.toolName().equals(expectedTool.trim())) {
                return Optional.empty();
            }
            boolean attached = waiter != null && waiter.resumeAttached() && !waiter.approved();
            if (requireResume && !attached) {
                return Optional.empty();
            }
            // remove(key,value) prevents two concurrent approvals from both
            // converting the same pending token.
            if (!pendingAskGrants.remove(key, candidate)) {
                return Optional.empty();
            }
            approved = candidate;
            persist = !attached;
            if (attached) {
                // 原调用还活着：唤醒它、由它在原线程继续执行。
                // 这条路径刻意不写持久化 grant —— 一次点击只授权这一次执行，
                // 否则 token 被重放就等于该参数组合被永久放行。
                approvedAskWaiters.put(key, new ApprovedAsk(candidate, true, true));
            } else {
                approvedAskWaiters.remove(key);
            }
            approvedAskWaiters.notifyAll();
        }
        if (persist) {
            AskGrant pending = approved;
            update(sessionId, old -> {
                Set<String> names = new HashSet<>(old.askGrantedTools());
                names.add(pending.toolName());
                Map<String, AskGrant> persisted = new HashMap<>(old.askGrants());
                long now = System.currentTimeMillis();
                persisted.entrySet().removeIf(e -> e.getValue().expired(now));
                if (persisted.size() >= MAX_APPROVED_ASK_GRANTS) {
                    var iterator = persisted.keySet().iterator();
                    if (iterator.hasNext()) {
                        persisted.remove(iterator.next());
                    }
                }
                persisted.put(pending.token(), pending);
                return new SessionPerm(old.mode(), old.planApproved(), names, persisted,
                        old.confirmPolicy(), old.execPolicyOverride());
            });
        }
        return Optional.of(approved);
    }

    /** Exact tool + argument authorization used by ToolPipeline. */
    public boolean isAskGranted(String sessionId, String toolName, String arguments) {
        if (StringUtils.isBlank(sessionId) || StringUtils.isBlank(toolName)) {
            return false;
        }
        SessionPerm permission = get(sessionId);
        String digest = argumentDigest(toolName, arguments);
        long now = System.currentTimeMillis();
        return permission.askGrants().values().stream()
                .filter(g -> !g.expired(now))
                .anyMatch(g -> g.toolName().equals(toolName.trim())
                        && g.argumentsDigest().equals(digest));
    }

    /** Optional token-aware form for API and audit callers. */
    public boolean isAskGranted(String sessionId, String toolName, String arguments,
                                String token) {
        if (StringUtils.isBlank(token)) {
            return false;
        }
        AskGrant grant = get(sessionId).askGrants().get(token.trim());
        return grant != null && !grant.expired(System.currentTimeMillis())
                && grant.toolName().equals(toolName == null ? "" : toolName.trim())
                && grant.argumentsDigest().equals(argumentDigest(toolName, arguments));
    }

    public boolean isAskGranted(String sessionId, String toolName) {
        if (Objects.isNull(toolName)) {
            return false;
        }
        return get(sessionId).askGrantedTools().contains(toolName);
    }

    /** Digest shared with ToolPipeline and the approval audit path. */
    public static String argumentDigest(String toolName, String arguments) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            String canonical = Objects.requireNonNullElse(toolName, "").trim()
                    + "\n" + Objects.requireNonNullElse(arguments, "");
            return java.util.HexFormat.of().formatHex(
                    md.digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String newToken() {
        return UUID.randomUUID().toString() + UUID.randomUUID();
    }

    private static String pendingKey(String sessionId, String token) {
        return sessionId + "\u0000" + token;
    }

    /**
     * 权限状态视图。
     *
     * <p>返回<b>可变</b>的 LinkedHashMap 而不是 {@code Map.of(...)} ——
     * {@code Map.of} 不接受 null 值，而这里要往外传"没有覆盖"这个状态；
     * 同时调用方（controller）还要往里补"全局默认 / 最终生效"两档，
     * 只存会话态的 store 不该知道全局配置。
     */
    public Map<String, Object> toView(String sessionId) {
        SessionPerm p = get(sessionId);
        ConfirmPolicy policy = p.confirmPolicy() == null
                ? ConfirmPolicy.DANGEROUS : p.confirmPolicy();
        Map<String, Object> view = new java.util.LinkedHashMap<>();
        view.put("success", true);
        view.put("sessionId", Optional.ofNullable(sessionId).orElse(""));
        view.put("mode", p.mode().wireName());
        view.put("label", p.mode().labelZh());
        view.put("planApproved", p.planApproved());
        view.put("planActive", p.mode() == PermissionMode.PLAN && !p.planApproved());
        view.put("askGrantedTools", Set.copyOf(p.askGrantedTools()));
        long now = System.currentTimeMillis();
        view.put("askGrants", p.askGrants().values().stream()
                .filter(g -> !g.expired(now))
                .map(g -> Map.of(
                        "tool", g.toolName(),
                        "argumentsDigest", g.argumentsDigest(),
                        "expiresAt", g.expiresAtEpochMillis()))
                .toList());
        view.put("confirmPolicy", policy.wireName());
        view.put("confirmPolicyLabel", policy.labelZh());
        // 空串 = 跟随全局默认，不是"策略为空"。前端据此显示"跟随全局"。
        view.put("execPolicyOverride",
                Optional.ofNullable(p.execPolicyOverride()).map(ExecPolicy::wireName).orElse(""));
        return view;
    }

    public void clear(String sessionId) {
        if (Objects.nonNull(sessionId)) {
            bySession.remove(sessionId);
            String prefix = sessionId + "\u0000";
            pendingAskGrants.keySet().removeIf(key -> key.startsWith(prefix));
            approvedAskWaiters.keySet().removeIf(key -> key.startsWith(prefix));
            synchronized (approvedAskWaiters) {
                approvedAskWaiters.notifyAll();
            }
            if (persistence != null) {
                persistence.delete(sessionId);
            }
        }
    }

    /**
     * 只丢进程内缓存的权限快照，保留持久化的那份。
     *
     * <p>用于「会话长期空闲」：权限里有用户明确点过「本会话一直允许」的授权集合，
     * 那不是缓存而是用户的意图。指针不能在这里删 —— 用户回来继续用同一个会话时
     * 被要求重新授权，属于功能退化。没有持久化实现时才退化成纯内存删（那份本来就是唯一副本）。</p>
     */
    public void forgetCached(String sessionId) {
        if (StringUtils.isNotBlank(sessionId)) {
            bySession.remove(sessionId);
        }
    }

    /**
     * 会话被删除或判定空闲时回收。
     *
     * <p>删除是不可逆的，连持久化授权一起丢；空闲只丢进程内快照。</p>
     */
    @EventListener
    public void onSessionEvicted(SessionEvictedEvent event) {
        if (event.isDeleted()) {
            clear(event.sessionId());
        } else {
            forgetCached(event.sessionId());
        }
    }
}
