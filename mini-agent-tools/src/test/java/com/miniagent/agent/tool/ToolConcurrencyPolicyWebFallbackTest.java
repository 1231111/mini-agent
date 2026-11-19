package com.miniagent.agent.tool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ToolConcurrencyPolicyWebFallbackTest {

    @Test
    void webToolsKeepEnoughTimeForTheirProviderFallbacks() {
        assertEquals(45, ToolConcurrencyPolicy.profileOf("web_search").executionBudgetSeconds());
        assertEquals(45, ToolConcurrencyPolicy.timeoutSecondsOf("web_search"));
        assertEquals(90, ToolConcurrencyPolicy.profileOf("web_extract").executionBudgetSeconds());
        assertEquals(90, ToolConcurrencyPolicy.timeoutSecondsOf("web_extract"));
    }

    @Test
    void longerExtractionBudgetDoesNotExpandGenericHttpReads() {
        assertEquals(30, ToolConcurrencyPolicy.timeoutSecondsOf("http_get"));
    }
}
