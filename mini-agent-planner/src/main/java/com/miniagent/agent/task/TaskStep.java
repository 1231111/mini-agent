package com.miniagent.agent.task;

import java.util.List;

/** 计划步骤。仅在子代理等需要显式步骤的场景使用。 */
public record TaskStep(
        int id,
        String goal,
        List<String> allowedTools,
        List<Integer> dependsOn
) {
}
