package com.miniagent.agent.core;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenEstimatorToolCallTest {
    @Test
    void longToolArgumentsContributeToContextBudget() {
        TokenEstimator estimator = new TokenEstimator();
        String small = "{\"path\":\"ui.html\",\"content\":\"x\"}";
        String large = "{\"path\":\"ui.html\",\"content\":\"" + "x".repeat(20_000) + "\"}";

        int shortMessage = estimator.estimate(message(small));
        int longMessage = estimator.estimate(message(large));

        assertTrue(longMessage - shortMessage >= 5_900,
                "write_file content is part of the prompt, not a fixed 30-token envelope");
        assertTrue(longMessage >= estimator.estimate(large));
    }

    private static AiMessage message(String arguments) {
        return AiMessage.from(ToolExecutionRequest.builder().id("write").name("write_file")
                .arguments(arguments).build());
    }
}
