package com.miniagent.agent.tool;

/** 工具副作用等级。未知工具必须按外部写入处理。 */
public enum ToolSideEffect {
    READ_ONLY,
    WRITE,
    EXTERNAL_WRITE
}
