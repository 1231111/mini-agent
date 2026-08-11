package com.miniagent.agent.core;

import java.nio.file.Path;

/**
 * 当前线程的 workspace 根覆盖（子 Agent 写入隔离目录）。
 */
public final class WorkspaceContext {

    private static final ThreadLocal<Path> ROOT_OVERRIDE = new ThreadLocal<>();
    private static final ThreadLocal<String> TASK_OVERRIDE = new ThreadLocal<>();

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
    }
}
