package com.miniagent.agent.hook;

/**
 * PreToolUse 裁决：放行 / 改写参数 / 拒绝执行。
 */
public record ToolPreDecision(boolean deny, String argumentsJson, String denyMessage) {

    public static ToolPreDecision proceed(String argumentsJson) {
        return new ToolPreDecision(false, argumentsJson, null);
    }

    public static ToolPreDecision rewrite(String newArgumentsJson) {
        return new ToolPreDecision(false, newArgumentsJson, null);
    }

    public static ToolPreDecision deny(String message) {
        return new ToolPreDecision(true, null, message);
    }
}
