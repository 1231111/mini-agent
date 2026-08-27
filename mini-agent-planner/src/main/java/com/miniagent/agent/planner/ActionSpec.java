package com.miniagent.agent.planner;

import java.util.Map;

/** 调度阶段的可执行动作及其验收、补偿、幂等元数据。 */
public record ActionSpec(
        String actionId,
        String taskId,
        String tool,
        String capability,
        Map<String, Object> arguments,
        DoneWhen acceptance,
        String compensation,
        String idempotencyKey,
        int timeoutSeconds,
        ActionRetryPolicy retryPolicy,
        String concurrencyKey
) {
    public ActionSpec {
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
        tool = tool == null ? "" : tool.trim();
        capability = capability == null ? "" : capability.trim();
        acceptance = acceptance == null ? DoneWhen.note() : acceptance;
        compensation = compensation == null ? "" : compensation;
        idempotencyKey = idempotencyKey == null ? "" : idempotencyKey;
        timeoutSeconds = timeoutSeconds > 0 ? timeoutSeconds : 60;
        retryPolicy = retryPolicy == null ? ActionRetryPolicy.none() : retryPolicy;
        concurrencyKey = concurrencyKey == null ? "" : concurrencyKey;
    }

    /** 兼容旧 Scheduler 调用。 */
    public ActionSpec(String actionId, String taskId, String tool, Map<String, Object> arguments,
                      String expectedResult, String compensation) {
        this(actionId, taskId, tool, "", arguments, DoneWhen.parseWire(expectedResult), compensation,
                "", 60, ActionRetryPolicy.none(), "");
    }

    public String expectedResult() { return acceptance.wire(); }
}
