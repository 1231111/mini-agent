package com.miniagent.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** 在调用 handler 前执行最小 JSON Schema 校验。 */
public final class ToolArgumentValidator {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ToolArgumentValidator() {}

    public static ValidationResult validate(Map<String, Object> schema, String argumentsJson) {
        ObjectNode args;
        try {
            JsonNode parsed = argumentsJson == null || argumentsJson.isBlank()
                    ? MAPPER.createObjectNode() : MAPPER.readTree(argumentsJson);
            if (parsed == null || !parsed.isObject()) return ValidationResult.failure("工具参数必须是 JSON object");
            args = (ObjectNode) parsed;
        } catch (Exception e) {
            return ValidationResult.failure("工具参数不是合法 JSON: " + e.getMessage());
        }
        if (schema == null || schema.isEmpty()) return ValidationResult.success(args);
        for (Map.Entry<String, Object> entry : schema.entrySet()) {
            if (!(entry.getValue() instanceof Map<?, ?> def)) continue;
            JsonNode value = args.get(entry.getKey());
            if ((value == null || value.isNull()) && Boolean.TRUE.equals(def.get("required"))) {
                return ValidationResult.failure("缺少必填参数: " + entry.getKey());
            }
            if (value == null || value.isNull()) continue;
            String type = Objects.toString(def.get("type"), "string").toLowerCase(Locale.ROOT);
            if (!matches(type, value)) return ValidationResult.failure("参数 " + entry.getKey() + " 类型错误，期望 " + type);
            if (def.get("enum") instanceof Collection<?> allowed
                    && allowed.stream().noneMatch(v -> Objects.equals(String.valueOf(v), value.asText()))) {
                return ValidationResult.failure("参数 " + entry.getKey() + " 不在允许值中: " + allowed);
            }
        }
        return ValidationResult.success(args);
    }

    private static boolean matches(String type, JsonNode value) {
        return switch (type) {
            case "integer" -> value.isIntegralNumber();
            case "number" -> value.isNumber();
            case "boolean" -> value.isBoolean();
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            default -> value.isTextual();
        };
    }

    public record ValidationResult(boolean valid, ObjectNode arguments, ToolResult error) {
        static ValidationResult success(ObjectNode arguments) { return new ValidationResult(true, arguments, null); }
        static ValidationResult failure(String message) {
            return new ValidationResult(false, null,
                    ToolResult.failure(ToolErrorCode.INVALID_ARGUMENT, message, false));
        }
    }
}
