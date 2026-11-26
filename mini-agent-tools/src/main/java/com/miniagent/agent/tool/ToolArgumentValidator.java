package com.miniagent.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;

/** 在调用 handler 前执行最小 JSON Schema 校验。 */
public final class ToolArgumentValidator {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ToolArgumentValidator() {}

    public static ValidationResult validate(Map<String, Object> schema, String argumentsJson) {
        ObjectNode args;
        try {
            JsonNode parsed = argumentsJson == null || argumentsJson.isBlank()
                    ? MAPPER.createObjectNode() : MAPPER.readTree(argumentsJson);
            if (parsed == null || !parsed.isObject()) {
                return ValidationResult.failure("工具参数必须是 JSON object");
            }
            args = (ObjectNode) parsed;
        } catch (Exception e) {
            return ValidationResult.failure("工具参数不是合法 JSON: " + e.getMessage());
        }
        // Flat built-in parameter maps and complete MCP root schemas must share
        // exactly one executable contract.  Keeping a second recursive validator
        // here caused nested objects, refs, enums and additionalProperties to
        // diverge between discovery and dispatch.  ToolSchemaContract wraps a
        // legacy flat map into the same object schema before validating it.
        String schemaFailure = ToolSchemaContract.validate(schema, args);
        return schemaFailure == null ? ValidationResult.success(args)
                : ValidationResult.failure(schemaFailure);
    }

    public record ValidationResult(boolean valid, ObjectNode arguments, ToolResult error) {
        static ValidationResult success(ObjectNode arguments) { return new ValidationResult(true, arguments, null); }
        static ValidationResult failure(String message) {
            return new ValidationResult(false, null,
                    ToolResult.failure(ToolErrorCode.INVALID_ARGUMENT, message, false));
        }
    }
}
