package com.miniagent.agent.permission;

import com.miniagent.common.event.SessionEvictedEvent;
import com.miniagent.common.permission.ExecPolicy;
import jakarta.annotation.PostConstruct;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

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
    private final SessionPermissionPersistence persistence;

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
     */
    public AskGrant issueAskGrant(String sessionId, String toolName,
                                  String arguments, long ttlMillis) {
        if (StringUtils.isBlank(sessionId) || StringUtils.isBlank(toolName)) {
            return null;
        }
        long ttl = Math.max(1_000L, ttlMillis);
        String digest = argumentDigest(toolName, arguments);
        long now = System.currentTimeMillis();
        // Reuse a still-pending approval for the same exact invocation. This
        // avoids an unbounded token stream while an SSE client is reconnecting.
        for (Map.Entry<String, AskGrant> entry : pendingAskGrants.entrySet()) {
            if (entry.getKey().startsWith(sessionId + "\u0000")
                    && !entry.getValue().expired(now)
                    && entry.getValue().toolName().equals(toolName.trim())
                    && entry.getValue().argumentsDigest().equals(digest)) {
                return entry.getValue();
            }
        }
        AskGrant grant = new AskGrant(
                newToken(), toolName.trim(), digest, now + ttl);
        pendingAskGrants.entrySet().removeIf(e -> e.getValue().expired(now));
        if (pendingAskGrants.size() >= MAX_PENDING_ASK_GRANTS) {
            // Capacity is a safety bound, not an authorization decision. Drop
            // one pending entry; the user can request it again and receive a
            // fresh token.
            var iterator = pendingAskGrants.keySet().iterator();
            if (iterator.hasNext()) {
                pendingAskGrants.remove(iterator.next());
            }
        }
        pendingAskGrants.put(pendingKey(sessionId, grant.token()), grant);
        return grant;
    }

    /**
     * Converts a pending token into an approved exact-argument grant. A token
     * is single-use; expired or cross-session tokens are rejected.
     */
    public Optional<AskGrant> grantAskToken(String sessionId, String token) {
        return grantAskToken(sessionId, token, null);
    }

    /** Same as {@link #grantAskToken(String, String)} with an optional UI tool consistency check. */
    public Optional<AskGrant> grantAskToken(String sessionId, String token,
                                            String expectedTool) {
        if (StringUtils.isBlank(sessionId) || StringUtils.isBlank(token)) {
            return Optional.empty();
        }
        String key = pendingKey(sessionId, token.trim());
        AskGrant candidate = pendingAskGrants.get(key);
        if (candidate == null || candidate.expired(System.currentTimeMillis())
                || (StringUtils.isNotBlank(expectedTool)
                && !candidate.toolName().equals(expectedTool.trim()))) {
            return Optional.empty();
        }
        // remove(key,value) prevents two concurrent approvals from both
        // converting the same pending token.
        if (!pendingAskGrants.remove(key, candidate)) {
            return Optional.empty();
        }
        AskGrant pending = candidate;
        update(sessionId, old -> {
            Set<String> names = new HashSet<>(old.askGrantedTools());
            names.add(pending.toolName());
            Map<String, AskGrant> approved = new HashMap<>(old.askGrants());
            long now = System.currentTimeMillis();
            approved.entrySet().removeIf(e -> e.getValue().expired(now));
            if (approved.size() >= MAX_APPROVED_ASK_GRANTS) {
                var iterator = approved.keySet().iterator();
                if (iterator.hasNext()) {
                    approved.remove(iterator.next());
                }
            }
            approved.put(pending.token(), pending);
            return new SessionPerm(old.mode(), old.planApproved(), names, approved,
                    old.confirmPolicy(), old.execPolicyOverride());
        });
        return Optional.of(pending);
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
