package com.miniagent.replica;

/** 跨实例会话运行锁相关常量。 */
public final class ReplicaLockKeys {

    public static final String SESSION_RUN_PREFIX = "run:session:";
    public static final String USER_RUNNING_PREFIX = "running:user:";

    private ReplicaLockKeys() {}

    public static String sessionRunKey(String sessionId) {
        return SESSION_RUN_PREFIX + sessionId;
    }

    public static String userRunningKey(long userId) {
        return USER_RUNNING_PREFIX + userId;
    }
}
