package com.miniagent.agent.context;

/**
 * system prompt 槽位。预算按 token 配，组装时用 chars-per-token 换成字符截断。
 */
public enum ContextSlot {
    IDENTITY(2000),
    AUTHORITY(400),
    QUESTION(800),
    REFERENCE(300),
    MEMORY(6000),
    SKILLS(2000),
    REASONING(1500),
    TOOLS(4000),
    TODO(2000),
    CLOSING(1500);

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
