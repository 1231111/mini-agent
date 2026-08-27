package com.miniagent.memory.model;

/**
 * 记忆来源。用于冲突解决的优先级判定。
 * 优先级：USER_STATED > SYSTEM_CONFIG > TOOL_OBSERVED > AGENT_INFERRED > LLM_EXTRACTED
 */
public enum SourceType {
    /** 用户明确声明 */
    USER_STATED(1.0),
    /** 系统配置读取 */
    SYSTEM_CONFIG(0.8),
    /** 工具执行实际观测 */
    TOOL_OBSERVED(0.7),
    /** Agent 推理得出 */
    AGENT_INFERRED(0.5),
    /** LLM 提取/总结 */
    LLM_EXTRACTED(0.3);

    private final double priority;

    SourceType(double priority) {
        this.priority = priority;
    }

    public double priority() {
        return priority;
    }
}
