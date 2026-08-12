package com.miniagent.agent.planner;

import java.util.Map;

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
