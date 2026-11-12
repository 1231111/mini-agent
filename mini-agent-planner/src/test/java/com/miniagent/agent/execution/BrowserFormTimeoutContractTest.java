package com.miniagent.agent.execution;

import com.miniagent.agent.tool.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BrowserFormTimeoutContractTest {
    @Test
    void lostFormActionReplyIsUnknownAndCannotBeReplayedAutomatically() {
        ToolRegistry registry = new ToolRegistry();
        for (String name : List.of("browser_select", "browser_set_checked", "browser_upload")) {
            registry.register(name, "form", Map.of(), ignored -> "{}");
            ToolDescriptor descriptor = registry.getDescriptor(name).orElseThrow();
            ToolResult timeout = ToolInvocationBudget.timeoutResult(descriptor, 30);
            assertEquals(ToolStatus.UNKNOWN, timeout.status(), name);
            assertEquals(ToolErrorCode.OUTCOME_UNKNOWN, timeout.errorCode(), name);
            assertFalse(timeout.retriable(), name);
            assertEquals(0, descriptor.maxRetries(), name);
        }
    }

    @Test
    void fieldInspectionTimeoutRemainsAReadOnlyRetryableFailure() {
        ToolRegistry registry = new ToolRegistry();
        for (String name : List.of("browser_form_inspect", "browser_form_verify")) {
            registry.register(name, "inspect", Map.of(), ignored -> "{}");
            ToolResult timeout = ToolInvocationBudget.timeoutResult(registry.getDescriptor(name).orElseThrow(), 15);
            assertEquals(ToolStatus.TIMEOUT, timeout.status(), name);
            assertTrue(timeout.retriable(), name);
        }
    }
}
