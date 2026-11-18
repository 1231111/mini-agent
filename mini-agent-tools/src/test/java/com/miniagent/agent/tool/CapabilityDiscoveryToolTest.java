package com.miniagent.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for the direct-route capability negotiation tool. */
class CapabilityDiscoveryToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void registerExposesReadOnlySchemaAndDiscoversRegisteredPack() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.register("read_file", "read", Map.of("type", "object"), ignored -> "ok");
        registry.register("write_file", "write", Map.of("type", "object"), ignored -> "ok");
        CapabilityRegistry capabilities = new CapabilityRegistry(registry);

        BuiltinTools builtins = new BuiltinTools();
        ReflectionTestUtils.setField(builtins, "registry", registry);
        ReflectionTestUtils.setField(builtins, "capabilityRegistry", capabilities);
        ReflectionTestUtils.invokeMethod(builtins, "registerCapabilityDiscovery");

        assertTrue(registry.hasTool("capability_discover"));
        ToolDescriptor descriptor = registry.getDescriptor("capability_discover").orElse(null);
        assertNotNull(descriptor);
        assertEquals(ToolSideEffect.READ_ONLY, descriptor.sideEffect());
        assertTrue(descriptor.idempotent());
        assertFalse(descriptor.parameters().isEmpty());
        assertTrue(descriptor.parameters().containsKey("capability"));
        assertTrue(descriptor.parameters().containsKey("query"));

        JsonNode result = JSON.readTree(registry.execute("capability_discover", "{\"capability\":\"file_read\"}"));
        assertTrue(result.path("success").asBoolean());
        assertEquals("file_read", result.path("capability").asText());
        assertTrue(result.path("tools").isArray());
        assertTrue(result.path("tools").toString().contains("read_file"));
        assertFalse(result.path("tools").toString().contains("write_file"));
    }

    @Test
    void malformedArgumentsReturnStructuredInvalidArgument() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        CapabilityRegistry capabilities = new CapabilityRegistry(registry);
        BuiltinTools builtins = new BuiltinTools();
        ReflectionTestUtils.setField(builtins, "registry", registry);
        ReflectionTestUtils.setField(builtins, "capabilityRegistry", capabilities);
        ReflectionTestUtils.invokeMethod(builtins, "registerCapabilityDiscovery");

        JsonNode result = JSON.readTree(registry.execute("capability_discover", "not-json"));
        assertEquals("INVALID_ARGUMENT", result.path("errorCode").asText());
    }

    @Test
    void policyTreatsDiscoveryAsReadOnlyAndPlanSafe() {
        assertEquals(ToolSideEffect.READ_ONLY,
                ToolConcurrencyPolicy.sideEffectOf("capability_discover"));
        assertTrue(ToolConcurrencyPolicy.isReadOnly("capability_discover"));
        assertTrue(ToolConcurrencyPolicy.isIdempotent("capability_discover"));
    }

    @Test
    void explicitSurfaceDoesNotImplicitlyExpandToEveryMcpTool() {
        ToolRegistry registry = new ToolRegistry();
        registry.register("write_file", "write", Map.of("type", "object"), ignored -> "ok");
        registry.register("web_extract", "extract", Map.of("type", "object"), ignored -> "ok");
        registry.register("mcp__untrusted__delete_everything", "external", Map.of("type", "object"),
                ignored -> "ok");

        var specs = registry.getSpecifications(Set.of("write_file", "web_extract"));
        assertEquals(Set.of("write_file", "web_extract"),
                specs.stream().map(s -> s.name()).collect(java.util.stream.Collectors.toSet()));
    }
}
