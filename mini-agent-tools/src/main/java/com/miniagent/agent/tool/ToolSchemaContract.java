package com.miniagent.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.internal.JsonSchemaElementJsonUtils;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** One schema contract for MCP discovery, model dispatch, and execution validation. */
public final class ToolSchemaContract {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String RAW_SCHEMA_KEY = "__miniagentJsonSchema";
    private static final int MAX_SCHEMA_DEPTH = 32;
    private static final int MAX_SCHEMA_NODES = 4096;
    private static final Set<String> SUPPORTED_KEYS = Set.of("type", "properties", "required", "items",
            "enum", "additionalProperties", "$ref", "$defs", "definitions", "description", "title",
            "default", "examples", "$schema");

    private ToolSchemaContract() {}

    /** Keep a remote root schema intact; property-name maps cannot represent root-level $defs. */
    public static Map<String, Object> fromJsonSchema(JsonNode schema) {
        if (schema == null || !schema.isObject()) {
            throw new IllegalArgumentException("MCP inputSchema 必须是 object schema");
        }
        ObjectNode root = ((ObjectNode) schema).deepCopy();
        requireSupported(root, root, new HashSet<>(), 0, new int[]{0});
        if (!"object".equals(root.path("type").asText(""))) {
            throw new IllegalArgumentException("MCP inputSchema 根类型必须明确为 object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> raw = JSON.convertValue(root, Map.class);
        return Map.of(RAW_SCHEMA_KEY, raw);
    }

    /** Supports both existing flat built-in params and complete MCP root schemas. */
    static ObjectNode rootSchema(Map<String, Object> parameters) {
        if (parameters != null && parameters.containsKey(RAW_SCHEMA_KEY)) {
            if (parameters.size() != 1 || !(parameters.get(RAW_SCHEMA_KEY) instanceof Map<?, ?>)) {
                throw new IllegalArgumentException("完整 JSON Schema 包装无效");
            }
            JsonNode raw = JSON.valueToTree(parameters.get(RAW_SCHEMA_KEY));
            if (!(raw instanceof ObjectNode object)) throw new IllegalArgumentException("完整 JSON Schema 根不是 object");
            return object;
        }
        // A few built-in registrations pass a complete JsonObjectSchema map
        // directly (type/properties/required), while normal registrations pass
        // a map keyed by parameter name. Distinguish them before wrapping.
        if (isCompleteRootSchema(parameters)) {
            JsonNode raw = JSON.valueToTree(parameters);
            return (ObjectNode) raw;
        }
        ObjectNode root = JSON.createObjectNode();
        root.put("type", "object");
        root.put("additionalProperties", false);
        ObjectNode properties = root.putObject("properties");
        List<String> required = new ArrayList<>();
        if (parameters != null) parameters.forEach((name, value) -> {
            if (!(value instanceof Map<?, ?>)) throw new IllegalArgumentException("参数 schema 必须是 object: " + name);
            ObjectNode definition = JSON.valueToTree(value);
            if (definition.path("required").isBoolean()) {
                if (definition.path("required").asBoolean()) required.add(name);
                definition.remove("required");
            }
            if (!definition.has("type") && !definition.has("$ref")) definition.put("type", "string");
            properties.set(name, definition);
        });
        if (!required.isEmpty()) root.set("required", JSON.valueToTree(required));
        return root;
    }

    private static boolean isCompleteRootSchema(Map<String, Object> parameters) {
        // A flat parameter named "type" has an object schema value, whereas a
        // complete root declares type as a string, even for an open empty object.
        return parameters != null && "object".equals(parameters.get("type"));
    }

    public static JsonObjectSchema modelSchema(Map<String, Object> parameters) {
        ObjectNode root = rootSchema(parameters);
        requireSupported(root, root, new HashSet<>(), 0, new int[]{0});
        if (!"object".equals(root.path("type").asText())) {
            throw new IllegalArgumentException("工具参数根类型必须为 object");
        }
        return modelObject(root, root);
    }

    private static JsonObjectSchema modelObject(ObjectNode node, ObjectNode root) {
        JsonObjectSchema.Builder model = JsonObjectSchema.builder();
        if (node.has("description")) model.description(node.path("description").asText());
        JsonNode additional = node.get("additionalProperties");
        if (additional != null) model.additionalProperties(additional.asBoolean());
        JsonNode required = node.get("required");
        if (required != null) {
            List<String> names = new ArrayList<>();
            required.forEach(name -> names.add(name.asText()));
            model.required(names);
        }
        JsonNode properties = node.get("properties");
        if (properties != null) properties.fields().forEachRemaining(field -> {
            model.addProperty(field.getKey(), modelElement((ObjectNode) field.getValue(), root));
        });
        Map<String, JsonSchemaElement> definitions = new LinkedHashMap<>();
        for (String key : List.of("$defs", "definitions")) {
            JsonNode defs = node.get(key);
            if (defs != null) defs.fields().forEachRemaining(field -> {
                if (definitions.putIfAbsent(field.getKey(), modelElement((ObjectNode) field.getValue(), root)) != null) {
                    throw new IllegalArgumentException("JSON Schema definitions 重名: " + field.getKey());
                }
            });
        }
        if (!definitions.isEmpty()) model.definitions(definitions);
        return model.build();
    }

    private static JsonSchemaElement modelElement(ObjectNode node, ObjectNode root) {
        if (node.has("$ref")) {
            // Inline acyclic local references so providers and validation share one meaning.
            return modelElement(resolveReference(root, node.path("$ref").asText()), root);
        }
        if ("object".equals(node.path("type").asText())) return modelObject(node, root);
        ObjectNode clean = node.deepCopy();
        clean.remove(List.of("title", "default", "examples", "$schema"));
        if ("array".equals(clean.path("type").asText()) && clean.has("items")) {
            // Normalizing the recursive element through the same adapter retains refs/items.
            clean.set("items", JSON.valueToTree(JsonSchemaElementJsonUtils.toMap(
                    modelElement((ObjectNode) clean.get("items"), root))));
        }
        @SuppressWarnings("unchecked") Map<String, Object> map = JSON.convertValue(clean, Map.class);
        return JsonSchemaElementJsonUtils.fromMap(map);
    }

    static String validate(Map<String, Object> parameters, ObjectNode arguments) {
        ObjectNode root;
        try {
            root = rootSchema(parameters);
            requireSupported(root, root, new HashSet<>(), 0, new int[]{0});
        } catch (RuntimeException unsupported) {
            return "工具 JSON Schema 无法安全校验: " + unsupported.getMessage();
        }
        return validateNode(root, root, arguments, "$", 0);
    }

    private static String validateNode(ObjectNode schema, ObjectNode root, JsonNode value, String path, int depth) {
        if (depth > MAX_SCHEMA_DEPTH) return "参数嵌套超过安全深度: " + path;
        if (schema.has("$ref")) return validateNode(resolveReference(root, schema.path("$ref").asText()), root, value, path, depth + 1);
        String type = schema.path("type").asText("");
        boolean validType = switch (type) {
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "integer" -> value.isIntegralNumber();
            case "number" -> value.isNumber();
            case "boolean" -> value.isBoolean();
            case "string" -> value.isTextual();
            case "null" -> value.isNull();
            default -> false;
        };
        if (!validType) return "参数 " + path + " 类型错误，期望 " + type;
        if (schema.has("enum")) {
            boolean found = false;
            for (JsonNode allowed : schema.get("enum")) {
                if (allowed.equals(value) || allowed.isNumber() && value.isNumber()
                        && allowed.decimalValue().compareTo(value.decimalValue()) == 0) found = true;
            }
            if (!found) return "参数 " + path + " 不在允许的 enum 中";
        }
        if (value.isObject()) {
            JsonNode props = schema.path("properties");
            for (JsonNode name : schema.path("required")) {
                if (!value.has(name.asText())) return "缺少必填参数: " + path + "." + name.asText();
            }
            var fields = value.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                JsonNode childSchema = props.get(field.getKey());
                if (childSchema == null) {
                    if (schema.has("additionalProperties") && !schema.path("additionalProperties").asBoolean()) {
                        return "未知参数: " + path + "." + field.getKey();
                    }
                } else {
                    String error = validateNode((ObjectNode) childSchema, root, field.getValue(), path + "." + field.getKey(), depth + 1);
                    if (error != null) return error;
                }
            }
        } else if (value.isArray() && schema.has("items")) {
            int index = 0;
            for (JsonNode item : value) {
                String error = validateNode((ObjectNode) schema.get("items"), root, item, path + "[" + index++ + "]", depth + 1);
                if (error != null) return error;
            }
        }
        return null;
    }

    private static void requireSupported(ObjectNode node, ObjectNode root, Set<String> refs, int depth, int[] nodes) {
        if (depth > MAX_SCHEMA_DEPTH || ++nodes[0] > MAX_SCHEMA_NODES) throw new IllegalArgumentException("JSON Schema 超出安全复杂度上限");
        node.fieldNames().forEachRemaining(key -> {
            if (!SUPPORTED_KEYS.contains(key)) throw new IllegalArgumentException("当前工具模型/校验器不支持 JSON Schema 关键字: " + key);
        });
        if (node.has("$ref")) {
            if (node.size() != 1 || !node.path("$ref").isTextual()) throw new IllegalArgumentException("$ref 必须为独立的本地引用");
            String ref = node.path("$ref").asText();
            if (!refs.add(ref)) throw new IllegalArgumentException("当前工具模型不支持递归 $ref: " + ref);
            requireSupported(resolveReference(root, ref), root, refs, depth + 1, nodes);
            refs.remove(ref);
            return;
        }
        String type = node.path("type").asText("");
        if (!Set.of("object", "array", "string", "integer", "number", "boolean", "null").contains(type)) {
            throw new IllegalArgumentException("JSON Schema 必须声明支持的单一 type，实际为: " + node.path("type"));
        }
        if (node.has("enum") && (!node.path("enum").isArray() || node.path("enum").isEmpty())) throw new IllegalArgumentException("enum 必须是非空数组");
        // LangChain's root JsonObjectSchema has no object-enum representation.
        // Reject this contract explicitly instead of silently losing a constraint
        // in modelObject while the execution validator continues to enforce it.
        if ("object".equals(type) && node.has("enum")) {
            throw new IllegalArgumentException("当前工具模型不支持 object enum");
        }
        if (node.has("additionalProperties") && !node.path("additionalProperties").isBoolean()) {
            throw new IllegalArgumentException("当前工具模型不支持 schema-valued additionalProperties");
        }
        if (node.has("required")) {
            if (!node.path("required").isArray()) throw new IllegalArgumentException("required 必须是字符串数组");
            for (JsonNode name : node.path("required")) if (!name.isTextual()) throw new IllegalArgumentException("required 必须是字符串数组");
        }
        for (String key : List.of("properties", "$defs", "definitions")) {
            JsonNode entries = node.get(key);
            if (entries == null) continue;
            if (!entries.isObject()) throw new IllegalArgumentException(key + " 必须是 object");
            entries.fields().forEachRemaining(field -> {
                if (!(field.getValue() instanceof ObjectNode child)) throw new IllegalArgumentException(key + "." + field.getKey() + " 必须是 object schema");
                requireSupported(child, root, refs, depth + 1, nodes);
            });
        }
        if (node.has("items")) {
            if (!(node.get("items") instanceof ObjectNode child)) throw new IllegalArgumentException("items 必须是 object schema");
            requireSupported(child, root, refs, depth + 1, nodes);
        }
    }

    private static ObjectNode resolveReference(ObjectNode root, String ref) {
        if (!ref.startsWith("#/$defs/") && !ref.startsWith("#/definitions/")) {
            throw new IllegalArgumentException("只支持本地 $defs/definitions 引用，拒绝: " + ref);
        }
        JsonNode resolved = root.at(ref.substring(1));
        if (!(resolved instanceof ObjectNode object)) throw new IllegalArgumentException("$ref 未找到 object schema: " + ref);
        return object;
    }
}
