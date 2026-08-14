package com.miniagent.agent.hook;

/**
 * 单次工具调用钩子上下文。
 */
public record ToolHookContext(
        String sessionId,
        String toolName,
        String argumentsJson,
        int turn,
        boolean subagent
) {}
