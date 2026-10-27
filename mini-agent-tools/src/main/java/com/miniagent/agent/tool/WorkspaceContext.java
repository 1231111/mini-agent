package com.miniagent.agent.tool;

import java.nio.file.Path;

/**
 * 当前线程的 workspace 根覆盖（子 Agent 写入隔离目录）。
 */
public final class WorkspaceContext {

    private static final ThreadLocal<Path> ROOT_OVERRIDE = new ThreadLocal<>();
    private static final ThreadLocal<String> TASK_OVERRIDE = new ThreadLocal<>();
    private static final ThreadLocal<String> SESSION_OVERRIDE = new ThreadLocal<>();

    private WorkspaceContext() {}

    public static void setRootOverride(Path root) {
        ROOT_OVERRIDE.set(root);
    }

    public static Path getRootOverride() {
        return ROOT_OVERRIDE.get();
    }

    public static void clearRootOverride() {
        ROOT_OVERRIDE.remove();
    }

    /**
     * 当前线程所属会话 id。
     *
     * <p><b>为什么工具层需要它</b>：工作区被覆盖到会话自选的目录之后，写进结果文本的
     * 媒体地址必须带上会话标识，受保护媒体端点才知道去哪个根下取文件。没有这个值时，
     * 端点只能查默认工作区，图片会稳定 404。</p>
     *
     * <p>由 {@code RunScope.applyLocals()} 与工作区根一起安装，两者必须同源，
     * 否则会出现"地址指向 A 会话、文件写在 B 目录"的错配。</p>
     */
    public static void setSessionOverride(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            SESSION_OVERRIDE.remove();
        } else {
            SESSION_OVERRIDE.set(sessionId);
        }
    }

    public static String getSessionOverride() {
        return SESSION_OVERRIDE.get();
    }

    public static void clearSessionOverride() {
        SESSION_OVERRIDE.remove();
    }

    public static void setTaskOverride(String task) {
        TASK_OVERRIDE.set(task);
    }

    public static String getTaskOverride() {
        return TASK_OVERRIDE.get();
    }

    public static void clearTaskOverride() {
        TASK_OVERRIDE.remove();
    }

    public static void clearAll() {
        clearRootOverride();
        clearTaskOverride();
        clearSessionOverride();
    }
}
