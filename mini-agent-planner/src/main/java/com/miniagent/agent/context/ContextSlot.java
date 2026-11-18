package com.miniagent.agent.context;

/**
 * system prompt 槽位。预算按 token 配，组装时用 chars-per-token 换成字符截断。
 */
public enum ContextSlot {
    IDENTITY(2000),
    AUTHORITY(400),
    REFERENCE(300),
    MEMORY(6000),
    SKILLS(2000),
    REASONING(1500),
    TOOLS(4000),
    TODO(2000),
    // Stable closing/confirmation/output policy, placed before request-specific context.
    CLOSING(2400),
    // Request-specific date anchor follows stable policy text so it does not invalidate
    // the reusable system-prompt prefix on providers with automatic prompt caching.
    TIME(400);

    private final int defaultTokenBudget;

    ContextSlot(int defaultTokenBudget) {
        this.defaultTokenBudget = defaultTokenBudget;
    }

    public int defaultTokenBudget() {
        return defaultTokenBudget;
    }

    public String configKey() {
        return name().toLowerCase();
    }
}
