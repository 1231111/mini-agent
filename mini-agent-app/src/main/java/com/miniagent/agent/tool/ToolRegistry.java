package com.miniagent.agent.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 工具注册表：统一注册、发现、调度所有可用工具。
 * Agent 循环通过此注册表执行工具调用。
 */
@Slf4j
@Component
public class ToolRegistry {

    private final Map<String, Tool> tools = new ConcurrentHashMap<>();
    /** ToolSpecification 缓存：避免每次 getSpecifications 重建 schema */
    private final Map<String, ToolSpecification> specCache = new ConcurrentHashMap<>();

    /**
     * 注册一个工具
     */
    public void register(Tool tool) {
        if (tool.getName() == null || tool.getName().isBlank()) {
            throw new IllegalArgumentException("工具名称不能为空");
        }
        tools.put(tool.getName(), tool);
        // 注册时预构建并缓存 ToolSpecification
        specCache.put(tool.getName(), toSpecification(tool));
        log.debug("注册工具: {} - {}", tool.getName(), tool.getDescription());
    }

    /** 注销工具（MCP 热插拔 / 关闭时清理） */
    public void unregister(String name) {
        if (name == null || name.isBlank()) return;
        tools.remove(name);
        specCache.remove(name);
        log.debug("注销工具: {}", name);
    }

    public void unregisterByPrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) return;
        List<String> names = tools.keySet().stream().filter(n -> n.startsWith(prefix)).toList();
        names.forEach(this::unregister);
    }

    /**
     * 便捷注册
     */
    public void register(String name, String description,
                         Map<String, Object> parameters,
                         java.util.function.Function<String, String> handler) {
        register(Tool.builder()
                .name(name)
                .description(description)
                .parameters(parameters)
                .handler(handler)
                .build());
    }

    /**
     * 执行工具调用
     * @param toolName 工具名称
     * @param argumentsJson 参数 JSON
     * @return 执行结果
     */
    public String execute(String toolName, String argumentsJson) {
        Tool tool = tools.get(toolName);
        if (tool == null) {
            return "未知工具: " + toolName + "。可用工具: " + availableToolNames();
        }
        // 延迟脱敏：只在 INFO 日志启用时执行正则替换
        if (log.isInfoEnabled()) {
            String safeArgs = redactSensitive(argumentsJson);
            log.info("执行工具: {} 参数: {}", toolName,
                    safeArgs != null && safeArgs.length() > 200
                            ? safeArgs.substring(0, 200) + "..." : safeArgs);
        }
        return tool.execute(argumentsJson);
    }

    private static String redactSensitive(String s) {
        if (s == null) return null;
        return s
                .replaceAll("(?i)(access_token=)[^&\\s\"'}]+", "$1***")
                .replaceAll("(?i)(secret=)[^&\\s\"'}]+", "$1***")
                .replaceAll("(?i)(api[_-]?key=)[^&\\s\"'}]+", "$1***")
                .replaceAll("(?i)(\"access_token\"\\s*:\\s*\")[^\"]+\"", "$1***\"")
                .replaceAll("(?i)(\"secret\"\\s*:\\s*\")[^\"]+\"", "$1***\"")
                .replaceAll("(?i)(\"api[_-]?key\"\\s*:\\s*\")[^\"]+\"", "$1***\"");
    }

    /**
     * 获取所有已注册工具的 ToolSpecification 列表（供 LangChain4j 使用）
     */
    public List<ToolSpecification> getSpecifications() {
        return new ArrayList<>(specCache.values());
    }

    public List<ToolSpecification> getSpecifications(Set<String> allowedToolNames) {
        if (allowedToolNames == null) {
            return getSpecifications();
        }
        if (allowedToolNames.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> names = new LinkedHashSet<>(allowedToolNames);
        // 全工具面（含写文件+网页）时附带已注册 MCP，避免静态白名单漏掉 mcp__*
        if (names.contains("write_file") && names.contains("web_extract")) {
            for (String n : specCache.keySet()) {
                if (n != null && n.startsWith("mcp__")) names.add(n);
            }
        }
        List<ToolSpecification> result = new ArrayList<>();
        for (String name : names) {
            ToolSpecification spec = specCache.get(name);
            if (spec != null) result.add(spec);
        }
        return result;
    }

    /**
     * 获取所有工具
     */
    public Collection<Tool> getAllTools() {
        return Collections.unmodifiableCollection(tools.values());
    }

    /**
     * 检查工具是否存在
     */
    public boolean hasTool(String name) {
        return tools.containsKey(name);
    }

    /**
     * 获取可用工具名称集合（供构建系统提示词时判断注入哪些指导块）
     */
    public Set<String> getToolNames() {
        return Collections.unmodifiableSet(tools.keySet());
    }

    /**
     * 获取可用工具名称列表
     */
    public String availableToolNames() {
        return tools.keySet().stream().sorted().collect(Collectors.joining(", "));
    }

    /**
     * 将 Tool 转换为 LangChain4j 的 ToolSpecification。
     * LangChain4j 0.x 曾提供 {@code ToolParameters}；自 1.x 起改用 {@link JsonObjectSchema}（见 ToolSpecification.Builder#parameters）。
     */
    @SuppressWarnings("unchecked")
    private ToolSpecification toSpecification(Tool tool) {
        ToolSpecification.Builder builder = ToolSpecification.builder()
                .name(tool.getName())
                .description(tool.getDescription());

        Map<String, Object> params = tool.getParameters();
        if (params != null && !params.isEmpty()) {
            JsonObjectSchema.Builder schema = JsonObjectSchema.builder();
            List<String> required = new ArrayList<>();

            for (Map.Entry<String, Object> entry : params.entrySet()) {
                String paramName = entry.getKey();
                Object paramDef = entry.getValue();

                if (paramDef instanceof Map) {
                    Map<String, Object> def = (Map<String, Object>) paramDef;
                    String type = String.valueOf(def.getOrDefault("type", "string")).toLowerCase(Locale.ROOT);
                    String desc = Objects.toString(def.getOrDefault("description", ""), "");
                    boolean isRequired = Boolean.TRUE.equals(def.get("required"));

                    switch (type) {
                        case "integer" -> {
                            if (desc.isBlank()) {
                                schema.addIntegerProperty(paramName);
                            } else {
                                schema.addIntegerProperty(paramName, desc);
                            }
                        }
                        case "number" -> {
                            if (desc.isBlank()) {
                                schema.addNumberProperty(paramName);
                            } else {
                                schema.addNumberProperty(paramName, desc);
                            }
                        }
                        case "boolean" -> {
                            if (desc.isBlank()) {
                                schema.addBooleanProperty(paramName);
                            } else {
                                schema.addBooleanProperty(paramName, desc);
                            }
                        }
                        default -> {
                            if (desc.isBlank()) {
                                schema.addStringProperty(paramName);
                            } else {
                                schema.addStringProperty(paramName, desc);
                            }
                        }
                    }

                    if (isRequired) {
                        required.add(paramName);
                    }
                }
            }

            if (!required.isEmpty()) {
                schema.required(required);
            }
            builder.parameters(schema.build());
        }

        return builder.build();
    }
}
