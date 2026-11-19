package com.miniagent.agent.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentLoopActionTimeoutPolicyTest {

    @Test
    void plannerEstimateCannotCutOffSearchProviderFallbacks() {
        assertEquals(45, AgentLoop.applyActionTimeoutCap("web_search", 45, 10));
        assertEquals(90, AgentLoop.applyActionTimeoutCap("web_extract", 90, 10));
    }

    @Test
    void plannerEstimateStillCapsOtherActions() {
        assertEquals(10, AgentLoop.applyActionTimeoutCap("http_get", 30, 10));
        assertEquals(30, AgentLoop.applyActionTimeoutCap("http_get", 30, 0));
    }
}
