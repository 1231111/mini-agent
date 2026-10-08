package com.miniagent.agent.execution;

import java.util.Set;

/** 当前工具处理器继承的父工具面；{@code null} 表示父调用不限制。 */
public final class ToolCallContext {

    private static final ThreadLocal<Set<String>> ALLOWED_TOOLS = new ThreadLocal<>();

    private ToolCallContext() {
    }

    public static Set<String> allowedTools() {
        return ALLOWED_TOOLS.get();
    }

    public static Binding bind(Set<String> allowedTools) {
        Set<String> previous = ALLOWED_TOOLS.get();
        if (allowedTools == null) {
            ALLOWED_TOOLS.remove();
        } else {
            ALLOWED_TOOLS.set(Set.copyOf(allowedTools));
        }
        return () -> {
            if (previous == null) {
                ALLOWED_TOOLS.remove();
            } else {
                ALLOWED_TOOLS.set(previous);
            }
        };
    }

    @FunctionalInterface
    public interface Binding extends AutoCloseable {
        @Override
        void close();
    }
}
