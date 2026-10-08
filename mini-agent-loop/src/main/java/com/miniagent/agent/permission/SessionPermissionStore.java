package com.miniagent.agent.permission;

import com.miniagent.common.permission.ExecPolicy;
import jakarta.annotation.PostConstruct;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

/**
 * 按 sessionId 保存权限模式、Plan 批准状态、Ask 一次放行集合、待办确认策略，
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
            ConfirmPolicy confirmPolicy,
            ExecPolicy execPolicyOverride
    ) {
        public SessionPerm {
            askGrantedTools = askGrantedTools == null
                    ? Set.of()
                    : Set.copyOf(askGrantedTools);
        }
    }

    private record Cached(SessionPerm permission, long version) {
    }

    private static final int MAX_CAS_ATTEMPTS = 5;

    private final ConcurrentHashMap<String, Cached> bySession =
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
                Set.of(), ConfirmPolicy.DANGEROUS, null);
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
                ConfirmPolicy.from(state.confirmPolicy()),
                execPolicy);
    }

    private SessionPermissionPersistence.State toState(
            SessionPerm permission, long version) {
        return new SessionPermissionPersistence.State(
                permission.mode().wireName(),
                permission.planApproved(),
                permission.askGrantedTools(),
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
                    base.mode(), base.planApproved(), base.askGrantedTools(), policy,
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
            if (policy != ExecPolicy.ALLOW) {
                // 收紧：丢掉旧的 exec 批准，逼它重新问一次
                grants.remove(PermissionPolicy.EXEC_TOOL);
            }
            return new SessionPerm(
                    base.mode(), base.planApproved(), grants, base.confirmPolicy(), policy);
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
        update(sessionId, old -> {
            Set<String> grants = new HashSet<>(old.askGrantedTools());
            grants.add(toolName.trim());
            return new SessionPerm(
                    old.mode(),
                    old.planApproved(),
                    grants,
                    old.confirmPolicy(),
                    old.execPolicyOverride());
        });
    }

    public boolean isAskGranted(String sessionId, String toolName) {
        if (Objects.isNull(toolName)) {
            return false;
        }
        return get(sessionId).askGrantedTools().contains(toolName);
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
}
