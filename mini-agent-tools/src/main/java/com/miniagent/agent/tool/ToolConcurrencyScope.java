package com.miniagent.agent.tool;

/** 工具共享资源的串行化范围。 */
public enum ToolConcurrencyScope {
    NONE,
    GLOBAL,
    SESSION,
    ARGUMENT
}
