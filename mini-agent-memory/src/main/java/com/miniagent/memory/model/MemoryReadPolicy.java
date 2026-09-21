package com.miniagent.memory.model;

/**
 * 本轮允许注入哪些记忆语义。由上下文加载策略生成，检索必须服从，不得旁路。
 */
public record MemoryReadPolicy(
        boolean working,
        boolean longTerm,
        boolean user,
        boolean midterm,
        int userMaxChars
) {
    public boolean any() {
        return working || longTerm || user || midterm;
    }

    public static MemoryReadPolicy none() {
        return new MemoryReadPolicy(false, false, false, false, 0);
    }
}
