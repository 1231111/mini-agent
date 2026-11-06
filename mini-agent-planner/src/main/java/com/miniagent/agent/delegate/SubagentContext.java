package com.miniagent.agent.delegate;

/**
 * 标记当前线程是否处于子 Agent 执行中（供 StopHook / 权限策略区分 SubagentStop）。
 */
public final class SubagentContext {

    private static final ThreadLocal<Boolean> ACTIVE = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<String> PARENT_SESSION = new ThreadLocal<>();
    private static final ThreadLocal<String> SUB_SESSION = new ThreadLocal<>();

    private SubagentContext() {}

    public static void enter(String parentSessionId, String subSessionId) {
        ACTIVE.set(true);
        PARENT_SESSION.set(parentSessionId);
        SUB_SESSION.set(subSessionId);
    }

    public static void exit() {
        ACTIVE.remove();
        PARENT_SESSION.remove();
        SUB_SESSION.remove();
    }

    public static boolean isActive() {
        return Boolean.TRUE.equals(ACTIVE.get());
    }

    public static String parentSessionId() {
        return PARENT_SESSION.get();
    }

    public static String subSessionId() {
        return SUB_SESSION.get();
    }
}
