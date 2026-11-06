package com.miniagent.agent.todo;
import org.apache.commons.lang3.StringUtils;

/**
 * 把当前对话的 sessionId 在调用 Agent 循环时注入 ThreadLocal，
 * 给 todo 工具内部使用，避免让模型自己传 sessionId。
 */
public final class TaskTodoContext {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private TaskTodoContext() {}

    public static void set(String sessionId) {
        CURRENT.set(sessionId);
    }

    public static String currentSessionId() {
        String sid = CURRENT.get();
        return StringUtils.isBlank(sid) ? "default" : sid;
    }

    public static void clear() {
        CURRENT.remove();
    }
}
