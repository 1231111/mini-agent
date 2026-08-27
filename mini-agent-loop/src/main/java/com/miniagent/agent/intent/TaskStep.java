package com.miniagent.agent.intent;

import java.util.List;

public record TaskStep(
        int id,
        String goal,
        List<String> allowedTools,
        List<Integer> dependsOn
){}
