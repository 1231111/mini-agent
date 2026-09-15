package com.miniagent.agent.permission;

import java.util.Objects;
import java.util.Optional;

/**
 * 权限上下文：主会话实时读 {@link SessionPermissionStore}；子 Agent 可用 {@link #force} 覆盖。
 */
public final class PermissionContext {

    private static volatile SessionPermissionStore STORE;

    private static final ThreadLocal<String> SESSION = new ThreadLocal<>();
    private static final ThreadLocal<PermissionMode> FORCE_MODE = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> FORCE_PLAN_OK = new ThreadLocal<>();

    private PermissionContext() {}

    public static void bindStore(SessionPermissionStore store) {
        STORE = store;
    }

    public static void setSession(String sessionId) {
        SESSION.set(sessionId);
        FORCE_MODE.remove();
        FORCE_PLAN_OK.remove();
    }

    public static void force(String sessionId, PermissionMode mode, boolean planApproved) {
        SESSION.set(sessionId);
        FORCE_MODE.set(Optional.ofNullable(mode).orElse(PermissionMode.DEFAULT));
        FORCE_PLAN_OK.set(planApproved);
    }

    /** 兼容旧调用：force 语义。主路径请用 {@link #setSession}。 */
    public static void set(String sessionId, PermissionMode mode, boolean planApproved) {
        force(sessionId, mode, planApproved);
    }

    public static String sessionId() {
        return SESSION.get();
    }

    public static PermissionMode mode() {
        PermissionMode forced = FORCE_MODE.get();
        if (forced != null) {
            return forced;
        }
        String sid = SESSION.get();
        if (sid == null || STORE == null) {
            return PermissionMode.DEFAULT;
        }
        return STORE.getMode(sid);
    }

    public static boolean planApproved() {
        return Optional.ofNullable(FORCE_PLAN_OK.get()).orElseGet(() ->
                Optional.ofNullable(SESSION.get())
                        .filter(sid -> Objects.nonNull(STORE))
                        .map(STORE::isPlanApproved)
                        .orElse(true));
    }

    public static ConfirmPolicy confirmPolicy() {
        String sid = SESSION.get();
        if (sid == null || STORE == null) {
            return ConfirmPolicy.DANGEROUS;
        }
        return STORE.getConfirmPolicy(sid);
    }

    public static boolean isForced() {
        return Objects.nonNull(FORCE_MODE.get());
    }

    public static void clear() {
        SESSION.remove();
        FORCE_MODE.remove();
        FORCE_PLAN_OK.remove();
    }
}
