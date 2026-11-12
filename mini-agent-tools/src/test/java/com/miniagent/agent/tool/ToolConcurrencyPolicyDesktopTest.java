package com.miniagent.agent.tool;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToolConcurrencyPolicyDesktopTest {
    private static final List<String> TOOLS = List.of("desktop_observe", "desktop_act", "desktop_release");

    @Test
    void everyDesktopToolLocksTheSameHostInputResource() {
        ToolRegistry registry = new ToolRegistry();
        for (String name : TOOLS) {
            registry.register(name, "desktop", Map.of(), ignored -> "{\"success\":true}");
            ToolDescriptor descriptor = registry.getDescriptor(name).orElseThrow();
            assertEquals(ToolConcurrencyScope.SHARED_RESOURCE, descriptor.concurrencyScope());
            assertEquals("desktop:local-input", descriptor.sharedResourceKey());
            assertEquals(0, descriptor.maxRetries(), "desktop actions must never be automatically replayed");
            assertFalse(descriptor.streamPrefetchSafe(), "screen content and input are not speculative tools");
        }
    }

    @Test
    void observingIsReadOnlyButActingIsNonIdempotentExternalInput() {
        assertEquals(ToolSideEffect.READ_ONLY, ToolConcurrencyPolicy.sideEffectOf("desktop_observe"));
        assertTrue(ToolConcurrencyPolicy.isIdempotent("desktop_observe"));
        assertEquals(ToolSideEffect.EXTERNAL_WRITE, ToolConcurrencyPolicy.sideEffectOf("desktop_act"));
        assertFalse(ToolConcurrencyPolicy.isIdempotent("desktop_act"));
        assertEquals(ToolTimeoutRecovery.ABORT, ToolConcurrencyPolicy.profileOf("desktop_act", "{}").timeoutRecovery());
        assertTrue(ToolConcurrencyPolicy.profileOf("desktop_act", "{}").recoveryHint().contains("desktop_observe"));
        assertEquals(ToolSideEffect.EXTERNAL_WRITE, ToolConcurrencyPolicy.sideEffectOf("desktop_release"));
        assertTrue(ToolConcurrencyPolicy.isIdempotent("desktop_release"));
    }

    @Test
    void desktopBudgetsAreBoundedIndependentlyOfOtherTools() {
        assertEquals(15, ToolConcurrencyPolicy.timeoutSecondsOf("desktop_observe"));
        assertEquals(10, ToolConcurrencyPolicy.timeoutSecondsOf("desktop_act"));
        assertEquals(5, ToolConcurrencyPolicy.timeoutSecondsOf("desktop_release"));
    }

    @Test
    void plannerGetsOnlyRegisteredDesktopToolsAndRefreshesAfterRegistration() {
        ToolRegistry registry = new ToolRegistry();
        registry.register("desktop_observe", "observe", Map.of(), ignored -> "{}");
        CapabilityRegistry capabilities = new CapabilityRegistry(registry);
        assertEquals(List.of("desktop_observe"), capabilities.toolsFor("desktop"));
        registry.register("desktop_act", "act", Map.of(), ignored -> "{}");
        registry.register("desktop_release", "release", Map.of(), ignored -> "{}");
        assertEquals(TOOLS, capabilities.toolsFor("desktop"));
        assertTrue(CapabilityRegistry.PLANNER_CAPABILITIES.contains("desktop"));
        assertTrue(CapabilityRegistry.knownCapability("desktop"));
        assertTrue(CapabilityRegistry.acquires("desktop"));
        assertFalse(CapabilityRegistry.writesFiles("desktop"));
    }
}
