package com.miniagent.agent.planner;

import java.util.Map;

/** 调度阶段 {@code tool} 存 capability（路由键），不是已锁定的工具名。 */
public record ActionSpec(
        String actionId,
        String taskId,
        String tool,
        Map<String, Object> arguments,
        String expectedResult,
        String compensation
) {
    public ActionSpec {
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
        expectedResult = expectedResult == null ? "" : expectedResult;
        compensation = compensation == null ? "" : compensation;
    }
}
