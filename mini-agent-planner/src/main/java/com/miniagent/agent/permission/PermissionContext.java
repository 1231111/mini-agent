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
    private static final ThreadLocal<ConfirmPolicy> FORCE_CONFIRM = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> FORCE_PLAN_OK = new ThreadLocal<>();

    private PermissionContext() {}

    public static void bindStore(SessionPermissionStore store) {
        STORE = store;
    }

    public static void setSession(String sessionId) {
        SESSION.set(sessionId);
        FORCE_MODE.remove();
        FORCE_CONFIRM.remove();
        FORCE_PLAN_OK.remove();
    }

    /**
     * 强制一份权限快照（子代理 / 派生作用域用）。
     *
     * <p>确认策略必须一起冻结：只冻模式会让子代理里的待办闸门去读子会话在存储里的记录，
     * 而子会话通常没有自己的记录 —— 于是父会话「全自动」在子代理里失效，又弹一次确认。</p>
     */
    public static void force(String sessionId, PermissionMode mode,
                             ConfirmPolicy confirmPolicy, boolean planApproved) {
        SESSION.set(sessionId);
        FORCE_MODE.set(Optional.ofNullable(mode).orElse(PermissionMode.DEFAULT));
        FORCE_CONFIRM.set(Optional.ofNullable(confirmPolicy).orElse(ConfirmPolicy.DANGEROUS));
        FORCE_PLAN_OK.set(planApproved);
    }

    /** 兼容旧调用：确认策略不允许强制，退回「会话存储 ?? 危险确认」。 */
    public static void force(String sessionId, PermissionMode mode, boolean planApproved) {
        SESSION.set(sessionId);
        FORCE_MODE.set(Optional.ofNullable(mode).orElse(PermissionMode.DEFAULT));
        FORCE_CONFIRM.remove();
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
        Boolean forced = FORCE_PLAN_OK.get();
        if (forced != null) {
            return forced;
        }
        String sid = SESSION.get();
        if (sid == null || STORE == null) {
            return true;
        }
        return STORE.isPlanApproved(sid);
    }

    public static ConfirmPolicy confirmPolicy() {
        ConfirmPolicy forced = FORCE_CONFIRM.get();
        if (forced != null) {
            return forced;
        }
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
        FORCE_CONFIRM.remove();
        FORCE_PLAN_OK.remove();
    }
}
