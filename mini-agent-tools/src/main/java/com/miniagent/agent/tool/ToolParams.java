package com.miniagent.agent.tool;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具参数基类：所有工具参数类的父类。
 * 提供JSON序列化和参数Schema生成能力。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public abstract class ToolParams {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 将参数对象转换为JSON字符串
     */
    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (Exception e) {
            throw new RuntimeException("参数序列化失败", e);
        }
    }

    /**
     * 从JSON字符串解析参数对象
     */
    public static <T extends ToolParams> T fromJson(String json, Class<T> clazz) {
        try {
            return MAPPER.readValue(json, clazz);
        } catch (Exception e) {
            throw new RuntimeException("参数反序列化失败: " + json, e);
        }
    }

    /**
     * 从Map解析参数对象
     */
    public static <T extends ToolParams> T fromMap(Map<String, Object> map, Class<T> clazz) {
        try {
            return MAPPER.convertValue(map, clazz);
        } catch (Exception e) {
            throw new RuntimeException("参数转换失败", e);
        }
    }

    /**
     * 根据字段注解生成JSON Schema格式的参数定义
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> generateSchema(Class<?> clazz) {
        Map<String, Object> schema = new LinkedHashMap<>();

        for (Field field : clazz.getDeclaredFields()) {
            ToolParamSchema annotation = field.getAnnotation(ToolParamSchema.class);
            if (annotation == null) {
                continue;
            }

            Map<String, Object> fieldDef = new LinkedHashMap<>();
            fieldDef.put("type", getJsonType(field.getType()));
            fieldDef.put("description", annotation.description());

            if (annotation.required()) {
                fieldDef.put("required", Boolean.TRUE);
            }

            if (!annotation.defaultValue().isEmpty()) {
                fieldDef.put("default", annotation.defaultValue());
            }

            schema.put(field.getName(), fieldDef);
        }

        return schema;
    }

    /**
     * Java类型到JSON Schema类型的映射
     */
    private static String getJsonType(Class<?> type) {
        if (type == String.class) {
            return "string";
        } else if (type == Integer.class || type == int.class) {
            return "integer";
        } else if (type == Long.class || type == long.class) {
            return "integer";
        } else if (type == Double.class || type == double.class) {
            return "number";
        } else if (type == Float.class || type == float.class) {
            return "number";
        } else if (type == Boolean.class || type == boolean.class) {
            return "boolean";
        } else {
            return "string";
        }
    }
}