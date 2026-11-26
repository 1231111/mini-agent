package com.miniagent.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.internal.JsonSchemaElementJsonUtils;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolSchemaContractTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void nestedObjectsArraysAndEnumsReachTheModelWithTheirActualTypes() throws Exception {
        Map<String, Object> parameters = ToolSchemaContract.fromJsonSchema(JSON.readTree(nestedSchema()));
        ToolRegistry registry = registry(parameters, new AtomicInteger());

        JsonNode schema = JSON.valueToTree(JsonSchemaElementJsonUtils.toMap(
                registry.getSpecifications().get(0).parameters()));

        assertThat(schema.path("type").asText()).isEqualTo("object");
        assertThat(schema.path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(schema.path("required").toString()).isEqualTo("[\"request\"]");
        JsonNode request = schema.path("properties").path("request");
        assertThat(request.path("type").asText()).isEqualTo("object");
        assertThat(request.path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(request.path("required").toString()).isEqualTo("[\"mode\",\"ids\",\"changes\"]");
        assertThat(request.path("properties").path("mode").path("enum").toString())
                .isEqualTo("[\"create\",\"update\"]");
        assertThat(request.path("properties").path("ids").path("items").path("type").asText())
                .isEqualTo("integer");
        JsonNode change = request.path("properties").path("changes").path("items");
        assertThat(change.path("type").asText()).isEqualTo("object");
        assertThat(change.path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(change.path("required").toString()).isEqualTo("[\"name\",\"enabled\"]");
        assertThat(change.path("properties").path("enabled").path("type").asText()).isEqualTo("boolean");
    }

    @Test
    void validNestedArgumentsExecuteButTypeEnumRequiredAndUnknownFieldFailuresNeverReachHandler() throws Exception {
        Map<String, Object> parameters = ToolSchemaContract.fromJsonSchema(JSON.readTree(nestedSchema()));
        AtomicInteger invocations = new AtomicInteger();
        ToolRegistry registry = registry(parameters, invocations);
        String valid = """
                {"request":{"mode":"update","ids":[1,2],"changes":[{"name":"one","enabled":true}]}}
                """;
        assertThat(registry.executeResult("schema_tool", valid).isSuccess()).isTrue();
        assertThat(invocations).hasValue(1);

        assertRejected(registry, """
                {"request":{"mode":"delete","ids":[1],"changes":[]}}
                """, "enum");
        assertRejected(registry, """
                {"request":{"mode":"update","ids":["1"],"changes":[]}}
                """, "$.request.ids[0]", "integer");
        assertRejected(registry, """
                {"request":{"mode":"update","ids":[1],"changes":[{"name":"one"}]}}
                """, "$.request.changes[0].enabled");
        assertRejected(registry, """
                {"request":{"mode":"update","ids":[1],"changes":[],"extra":true}}
                """, "$.request.extra");
        assertRejected(registry, """
                {"request":{"mode":"update","ids":[1],"changes":[]},"extra":true}
                """, "$.extra");
        assertThat(invocations).hasValue(1);
    }

    @Test
    void localReferencesAreResolvedForBothModelAndValidationIncludingLegacyDefinitions() throws Exception {
        String schemaText = """
                {"type":"object","additionalProperties":false,"required":["primary","secondary"],
                 "$defs":{"Profile":{"type":"object","required":["role"],"additionalProperties":false,
                   "properties":{"role":{"type":"string","enum":["reader","writer"]}}}},
                 "definitions":{"Ids":{"type":"array","items":{"type":"integer"}}},
                 "properties":{"primary":{"$ref":"#/$defs/Profile"},"secondary":{"$ref":"#/definitions/Ids"}}}
                """;
        ToolRegistry registry = registry(ToolSchemaContract.fromJsonSchema(JSON.readTree(schemaText)),
                new AtomicInteger());
        JsonNode schema = JSON.valueToTree(JsonSchemaElementJsonUtils.toMap(
                registry.getSpecifications().get(0).parameters()));
        assertThat(schema.path("properties").path("primary").path("type").asText()).isEqualTo("object");
        assertThat(schema.path("properties").path("primary").path("properties").path("role")
                .path("enum").toString()).isEqualTo("[\"reader\",\"writer\"]");
        assertThat(schema.path("properties").path("secondary").path("items").path("type").asText())
                .isEqualTo("integer");
        assertThat(registry.executeResult("schema_tool",
                "{\"primary\":{\"role\":\"reader\"},\"secondary\":[1,2]}").isSuccess()).isTrue();
        assertRejected(registry, "{\"primary\":{\"role\":\"admin\"},\"secondary\":[1,2]}", "enum");
        assertRejected(registry, "{\"primary\":{\"role\":\"writer\"},\"secondary\":[true]}", "$.secondary[0]");
    }

    @Test
    void additionalPropertiesTrueIsPreservedAndAllowsUnknownNestedData() throws Exception {
        ToolRegistry registry = registry(ToolSchemaContract.fromJsonSchema(JSON.readTree("""
                {"type":"object","additionalProperties":true,"properties":{
                  "metadata":{"type":"object","additionalProperties":true,"properties":{}}}}
                """)), new AtomicInteger());
        JsonNode schema = JSON.valueToTree(JsonSchemaElementJsonUtils.toMap(
                registry.getSpecifications().get(0).parameters()));
        assertThat(schema.path("additionalProperties").asBoolean()).isTrue();
        assertThat(schema.path("properties").path("metadata").path("additionalProperties").asBoolean()).isTrue();
        assertThat(registry.executeResult("schema_tool",
                "{\"metadata\":{\"arbitrary\":[1,true]},\"extra\":\"allowed\"}").isSuccess()).isTrue();
    }

    @Test
    void unsupportedConstraintsExternalAndRecursiveReferencesFailBeforeRegistrationOrExecution() throws Exception {
        for (String schemaText : new String[]{
                "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\",\"pattern\":\"a+\"}}}",
                "{\"type\":\"object\",\"additionalProperties\":{\"type\":\"string\"}}",
                "{\"type\":\"object\",\"enum\":[{}]}",
                "{\"type\":\"object\",\"properties\":{\"name\":{\"$ref\":\"https://example.com/schema\"}}}",
                "{\"type\":\"object\",\"$defs\":{\"Loop\":{\"type\":\"object\",\"properties\":{\"next\":{\"$ref\":\"#/$defs/Loop\"}}}}}",
                "{\"type\":\"object\",\"properties\":{\"unknown\":{\"$ref\":\"#/$defs/Missing\"}}}"
        }) {
            JsonNode invalid = JSON.readTree(schemaText);
            assertThatThrownBy(() -> ToolSchemaContract.fromJsonSchema(invalid))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        ToolRegistry registry = new ToolRegistry();
        assertThatThrownBy(() -> registry.register("unsupported", "test", Map.of(
                        "name", Map.of("type", "string", "pattern", "a+")), ignored -> "ok"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("pattern");
        assertThat(registry.hasTool("unsupported")).isFalse();
    }

    @Test
    void legacyParameterMapsAndCompleteRootSchemasKeepBuiltInRegistrationCompatible() {
        ToolRegistry registry = new ToolRegistry();
        registry.register("flat", "test", Map.of("ids", Map.of("type", "array", "items", Map.of(
                "type", "integer"), "required", true)), ignored -> "ok");
        registry.register("root", "test", Map.of("type", "object", "properties", Map.of(),
                "additionalProperties", false, "required", java.util.List.of()), ignored -> "ok");
        JsonNode flat = JSON.valueToTree(JsonSchemaElementJsonUtils.toMap(registry.getSpecifications().stream()
                .filter(spec -> spec.name().equals("flat")).findFirst().orElseThrow().parameters()));
        assertThat(flat.path("properties").path("ids").path("items").path("type").asText()).isEqualTo("integer");
        assertThat(registry.executeResult("flat", "{\"ids\":[1]}").isSuccess()).isTrue();
        assertToolRejected(registry, "flat", "{\"ids\":[\"1\"]}", "integer");
        assertThat(registry.executeResult("root", "{}").isSuccess()).isTrue();
        assertToolRejected(registry, "root", "{\"unexpected\":true}", "unexpected");

        registry.register("open_root", "test", Map.of("type", "object"), ignored -> "ok");
        assertThat(registry.executeResult("open_root", "{\"arbitrary\":true}").isSuccess()).isTrue();
    }

    @Test
    void numericEnumsDoNotCoerceDifferentValuesOrLosePrecision() throws Exception {
        ToolRegistry registry = registry(ToolSchemaContract.fromJsonSchema(JSON.readTree("""
                {"type":"object","properties":{"value":{"type":"integer","enum":[9007199254740992,9007199254740994]}}}
                """)), new AtomicInteger());
        JsonNode schema = JSON.valueToTree(JsonSchemaElementJsonUtils.toMap(
                registry.getSpecifications().get(0).parameters()));
        assertThat(schema.path("properties").path("value").path("type").asText()).isEqualTo("integer");
        assertThat(schema.path("properties").path("value").path("enum").toString())
                .isEqualTo("[9007199254740992,9007199254740994]");
        assertThat(registry.executeResult("schema_tool", "{\"value\":9007199254740992}").isSuccess()).isTrue();
        assertRejected(registry, "{\"value\":9007199254740993}", "enum");
        assertRejected(registry, "{\"value\":\"9007199254740992\"}", "integer");
    }

    @Test
    void legacyOptionalNullValuesFollowTheSameDeclaredTypesAsMcpArguments() {
        ToolRegistry registry = new ToolRegistry();
        registry.register("flat_null", "test", Map.of("name", Map.of("type", "string")), ignored -> "ok");
        assertThat(registry.executeResult("flat_null", "{}").isSuccess()).isTrue();
        assertToolRejected(registry, "flat_null", "{\"name\":null}", "$.name", "string");
    }

    private static ToolRegistry registry(Map<String, Object> parameters, AtomicInteger invocations) {
        ToolRegistry registry = new ToolRegistry();
        registry.register("schema_tool", "test", parameters, ignored -> {
            invocations.incrementAndGet();
            return "ok";
        });
        return registry;
    }

    private static void assertRejected(ToolRegistry registry, String arguments, String... message) {
        assertToolRejected(registry, "schema_tool", arguments, message);
    }

    private static void assertToolRejected(ToolRegistry registry, String tool, String arguments, String... message) {
        ToolResult result = registry.executeResult(tool, arguments);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.errorCode()).isEqualTo(ToolErrorCode.INVALID_ARGUMENT);
        assertThat(result.message()).contains(message);
    }

    private static String nestedSchema() {
        return """
                {"type":"object","required":["request"],"additionalProperties":false,
                 "properties":{"request":{"type":"object","required":["mode","ids","changes"],
                  "additionalProperties":false,"properties":{
                    "mode":{"type":"string","enum":["create","update"]},
                    "ids":{"type":"array","items":{"type":"integer"}},
                    "changes":{"type":"array","items":{"type":"object","required":["name","enabled"],
                      "additionalProperties":false,"properties":{"name":{"type":"string"},"enabled":{"type":"boolean"}}}}}}}}
                """;
    }
}
