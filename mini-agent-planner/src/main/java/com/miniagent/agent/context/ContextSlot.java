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
    // Includes the output/media contract plus the relative-date anchor; keep enough room
    // so mandatory suffix guidance is not silently clipped from every model request.
    CLOSING(2400);

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
