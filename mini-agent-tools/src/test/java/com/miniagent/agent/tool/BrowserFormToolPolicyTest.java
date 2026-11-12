package com.miniagent.agent.tool;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BrowserFormToolPolicyTest {
    private static final List<String> READS = List.of("browser_form_inspect", "browser_form_verify");
    private static final List<String> ACTIONS = List.of("browser_select", "browser_set_checked", "browser_upload");

    @Test
    void everyFormToolSerializesAgainstOtherOperationsOnItsBrowserPage() {
        ToolRegistry registry = new ToolRegistry();
        for (String name : List.of("browser_form_inspect", "browser_form_verify", "browser_select",
                "browser_set_checked", "browser_upload")) {
            registry.register(name, "form", Map.of(), ignored -> "{\"success\":true}");
            ToolDescriptor descriptor = registry.getDescriptor(name).orElseThrow();
            assertEquals(ToolConcurrencyScope.SESSION, descriptor.concurrencyScope(), name);
            assertEquals(0, descriptor.maxRetries(), name);
        }
    }

    @Test
    void readingFieldsIsReadOnlyButSelectingAndUploadingAreExternalActions() {
        for (String name : READS) {
            assertEquals(ToolSideEffect.READ_ONLY, ToolConcurrencyPolicy.sideEffectOf(name), name);
            assertTrue(ToolConcurrencyPolicy.isIdempotent(name), name);
        }
        for (String name : ACTIONS) {
            assertEquals(ToolSideEffect.EXTERNAL_WRITE, ToolConcurrencyPolicy.sideEffectOf(name), name);
            assertFalse(ToolConcurrencyPolicy.isIdempotent(name), name);
            assertFalse(ToolConcurrencyPolicy.isStreamPrefetchSafe(name), name);
            assertEquals(ToolTimeoutRecovery.ABORT, ToolConcurrencyPolicy.timeoutRecoveryOf(name), name);
            assertTrue(ToolConcurrencyPolicy.profileOf(name).recoveryHint().contains("browser_form_verify"), name);
        }
    }

    @Test
    void browserAndResearchPacksExposeRegisteredFormToolsWithoutFallback() {
        ToolRegistry registry = new ToolRegistry();
        CapabilityRegistry capabilities = new CapabilityRegistry(registry);
        for (String name : READS) registry.register(name, "form", Map.of(), ignored -> "{}");
        for (String name : ACTIONS) registry.register(name, "form", Map.of(), ignored -> "{}");
        for (String capability : List.of("browser", "web", "research")) {
            List<String> tools = capabilities.toolsFor(capability);
            assertTrue(tools.containsAll(READS), capability);
            assertTrue(tools.containsAll(ACTIONS), capability);
            assertFalse(tools.contains("todo"), capability);
        }
    }
}
