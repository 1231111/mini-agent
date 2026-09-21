package com.miniagent.agent.execution;

import com.miniagent.agent.tool.ToolResult;

/**
 * 管道出口。循环只根据 {@link Outcome} 更新自己的状态，不把 LoopState 传入管道。
 */
public record ToolInvocation(
        Outcome outcome,
        String toolName,
        ToolResult result,
        String loopSignal
) {
    public enum Outcome {
        /** 已执行（含 journal 去重命中）。 */
        EXECUTED,
        /** 规划硬闸门拒绝，计入死循环闸门计数。 */
        GATE_DENIED,
        /** Hook / Plan / 探测限额等策略拒绝，不当闸门锁死。 */
        POLICY_DENIED,
        /** 等用户批准工具。 */
        PERMISSION_ASK,
        /** 等用户回答问题。 */
        USER_QUESTION,
        /** 执行控制面截停。 */
        CONTROL_STOP,
        /** 规划 dispatch fence 失效。 */
        FENCE_REJECTED
    }

    public ToolInvocation {
        toolName = toolName == null ? "" : toolName;
        loopSignal = loopSignal == null ? "" : loopSignal;
        if (result == null) {
            result = ToolResult.failure(
                    com.miniagent.agent.tool.ToolErrorCode.INTERNAL_ERROR,
                    "empty tool result", false);
        }
    }

    public String text() {
        return result.legacyText();
    }

    public static ToolInvocation executed(String name, ToolResult result) {
        return new ToolInvocation(Outcome.EXECUTED, name, result, "");
    }

    public static ToolInvocation gateDenied(String name, ToolResult result) {
        return new ToolInvocation(Outcome.GATE_DENIED, name, result, "");
    }

    public static ToolInvocation policyDenied(String name, ToolResult result) {
        return new ToolInvocation(Outcome.POLICY_DENIED, name, result, "");
    }

    public static ToolInvocation permissionAsk(String name, ToolResult result) {
        return new ToolInvocation(Outcome.PERMISSION_ASK, name, result, name);
    }

    public static ToolInvocation userQuestion(String name, ToolResult result, String display) {
        return new ToolInvocation(Outcome.USER_QUESTION, name, result, display);
    }

    public static ToolInvocation controlStop(String name, ToolResult result) {
        return new ToolInvocation(Outcome.CONTROL_STOP, name, result, "");
    }

    public static ToolInvocation fenceRejected(String name, ToolResult result) {
        return new ToolInvocation(Outcome.FENCE_REJECTED, name, result, "");
    }
}
