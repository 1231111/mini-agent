package com.miniagent.agent.permission;

/**
 * 本轮请求的权限上下文（ThreadLocal，随工具虚拟线程显式传递）。
 */
public final class PermissionContext {

    private static final ThreadLocal<String> SESSION = new ThreadLocal<>();
    private static final ThreadLocal<PermissionMode> MODE = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> PLAN_APPROVED = new ThreadLocal<>();

    private PermissionContext() {}

    public static void set(String sessionId, PermissionMode mode, boolean planApproved) {
        SESSION.set(sessionId);
        MODE.set(mode == null ? PermissionMode.DEFAULT : mode);
        PLAN_APPROVED.set(planApproved);
    }

    public static String sessionId() {
        return SESSION.get();
    }

    public static PermissionMode mode() {
        PermissionMode m = MODE.get();
        return m == null ? PermissionMode.DEFAULT : m;
    }

    public static boolean planApproved() {
        Boolean b = PLAN_APPROVED.get();
        return b == null || b;
    }

    public static void clear() {
        SESSION.remove();
        MODE.remove();
        PLAN_APPROVED.remove();
    }
}
