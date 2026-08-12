package com.miniagent.agent.planner;

import java.util.List;
import java.util.Map;

/**
 * 结构化目标：Intent 下游、TaskGraph 上游。
 */
public record Goal(
        String goalId,
        String objective,
        String intent,
        Map<String, String> entities,
        List<String> constraints,
        List<String> successCriteria
) {
    public Goal {
        entities = entities == null ? Map.of() : Map.copyOf(entities);
        constraints = constraints == null ? List.of() : List.copyOf(constraints);
        successCriteria = successCriteria == null ? List.of() : List.copyOf(successCriteria);
    }
}
