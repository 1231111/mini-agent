package com.miniagent.agent.tool;

import lombok.extern.slf4j.Slf4j;
import com.miniagent.common.SecurityUtils;
import org.springframework.stereotype.Component;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;

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
        Objects.requireNonNull(tool, "tool");
        if (StringUtils.isBlank(tool.getName())) {
            throw new IllegalArgumentException("工具名称不能为空");
        }
        tool.descriptor();
        ToolSpecification specification = toSpecification(tool);
        Tool previous = tools.putIfAbsent(tool.getName(), tool);
        if (previous != null) {
            throw new IllegalStateException("工具重复注册: " + tool.getName());
        }
        specCache.put(tool.getName(), specification);
        log.debug("注册工具: {} - {}", tool.getName(), tool.getDescription());
    }

    /** 注销工具（MCP 热插拔 / 关闭时清理） */
    public void unregister(String name) {
        if (StringUtils.isBlank(name)) {
            return;
        }
        tools.remove(name);
        specCache.remove(name);
        log.debug("注销工具: {}", name);
    }

    public void unregisterByPrefix(String prefix) {
        if (StringUtils.isBlank(prefix)) {
            return;
        }
        List<String> names = tools.keySet().stream().filter(n -> n.startsWith(prefix)).toList();
        names.forEach(this::unregister);
    }

    /**
     * 便捷注册（使用Map定义参数）
     */
    public void register(String name, String description,
                         Map<String, Object> parameters,
                         java.util.function.Function<String, String> handler) {
        register(Tool.builder()
                .name(name)
                .description(description)
                .parameters(parameters)
                .sideEffect(ToolConcurrencyPolicy.sideEffectOf(name))
                .idempotent(ToolConcurrencyPolicy.isIdempotent(name))
                .streamPrefetchSafe(ToolConcurrencyPolicy.isStreamPrefetchSafe(name))
                .timeoutSeconds(ToolConcurrencyPolicy.timeoutSecondsOf(name))
                .maxRetries(ToolConcurrencyPolicy.maxRetriesOf(name))
                .concurrencyScope(ToolConcurrencyPolicy.concurrencyScopeOf(name))
                .concurrencyKeyArgument(ToolConcurrencyPolicy.concurrencyKeyArgumentOf(name))
                .handler(handler)
                .build());
    }

    /**
     * 类型安全注册：使用参数实体类定义参数
     *
     * @param name        工具名称
     * @param description 工具描述
     * @param paramsClass 参数实体类（继承自ToolParams）
     * @param handler     执行函数，接收参数实体类，返回结果字符串
     */
    public <T extends ToolParams> void register(String name, String description,
                                                 Class<T> paramsClass,
                                                 java.util.function.Function<T, String> handler) {
        Map<String, Object> schema = ToolParams.generateSchema(paramsClass);
        register(Tool.builder()
                .name(name)
                .description(description)
                .parameters(schema)
                .sideEffect(ToolConcurrencyPolicy.sideEffectOf(name))
                .idempotent(ToolConcurrencyPolicy.isIdempotent(name))
                .streamPrefetchSafe(ToolConcurrencyPolicy.isStreamPrefetchSafe(name))
                .timeoutSeconds(ToolConcurrencyPolicy.timeoutSecondsOf(name))
                .maxRetries(ToolConcurrencyPolicy.maxRetriesOf(name))
                .concurrencyScope(ToolConcurrencyPolicy.concurrencyScopeOf(name))
                .concurrencyKeyArgument(ToolConcurrencyPolicy.concurrencyKeyArgumentOf(name))
                .handler(json -> {
                    T params = ToolParams.fromJson(json, paramsClass);
                    return handler.apply(params);
                })
                .build());
    }

    /**
     * 执行工具调用
     * @param toolName 工具名称
     * @param argumentsJson 参数 JSON
     * @return 执行结果
     */
    public String execute(String toolName, String argumentsJson) {
        return executeResult(toolName, argumentsJson).legacyText();
    }

    public ToolResult executeResult(String toolName, String argumentsJson) {
        Tool tool = tools.get(toolName);
        if (Objects.isNull(tool)) {
            return ToolResult.failure(ToolErrorCode.UNKNOWN_TOOL,
                    "未知工具: " + toolName + "。可用工具: " + availableToolNames(), false);
        }
        // 延迟脱敏：只在 INFO 日志启用时执行正则替换
        if (log.isInfoEnabled()) {
            String safeArgs = redactSensitive(argumentsJson);
            log.info("执行工具: {} 参数: {}", toolName,
                    Objects.nonNull(safeArgs) && safeArgs.length() > 200
                            ? safeArgs.substring(0, 200) + "..." : safeArgs);
        }
        ToolResult result = tool.executeResult(argumentsJson);
        log.debug("工具执行完成: {} status={} errorCode={}",
                toolName, result.status(), result.errorCode());
        return result;
    }

    private static String redactSensitive(String s) {
        return SecurityUtils.redactSensitive(s);
    }

    /**
     * 取某次调用的超时秒数：优先用参数算出来的自适应值，没有就用注册期的静态值。
     *
     * <p>{@code AgentLoop} 用它设外层闸门。返回的必须是<b>外层</b>预算（比工具自身宽），
     * 否则外层先触发会把超时升级成「终态未知」。</p>
     */
    public long timeoutSeconds(String toolName, String argumentsJson) {
        Tool tool = tools.get(toolName);
        if (Objects.isNull(tool)) {
            return 60L;
        }
        Function<String, Long> adaptive = tool.getAdaptiveTimeoutSeconds();
        if (Objects.nonNull(adaptive)) {
            try {
                Long value = adaptive.apply(argumentsJson);
                if (Objects.nonNull(value) && value > 0) {
                    return value;
                }
            } catch (Exception e) {
                log.debug("自适应超时计算失败，回退静态值: {} - {}", toolName, e.getMessage());
            }
        }
        return tool.getTimeoutSeconds();
    }

    /**
     * 获取所有已注册工具的 ToolSpecification 列表（供 LangChain4j 使用）。
     *
     * 顺序固定：内置工具在前、MCP 工具在后，各自按名称排序。
     * 工具清单直接参与模型侧提示词前缀，顺序一旦抖动会整段击穿前缀缓存，
     * 所以这里不能直接暴露哈希表的迭代序。
     */
    public List<ToolSpecification> getSpecifications() {
        return stableOrder(specCache.values());
    }

    public List<ToolSpecification> getSpecifications(Set<String> allowedToolNames) {
        if (Objects.isNull(allowedToolNames)) {
            return getSpecifications();
        }
        if (allowedToolNames.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> names = new LinkedHashSet<>(allowedToolNames);
        // 全工具面（含写文件+网页）时附带已注册 MCP，避免静态白名单漏掉 mcp__*
        if (names.contains("write_file") && names.contains("web_extract")) {
            for (String n : specCache.keySet()) {
                if (Objects.nonNull(n) && n.startsWith("mcp__")) {
                    names.add(n);
                }
            }
        }
        List<ToolSpecification> result = new ArrayList<>();
        for (String name : names) {
            ToolSpecification spec = specCache.get(name);
            if (Objects.nonNull(spec)) {
                result.add(spec);
            }
        }
        return stableOrder(result);
    }

    /**
     * 工具清单定序：内置在前、MCP 在后，同类按名称排序。
     * 上层装配工具池时同样遵循「内置段 + MCP 段各自有序」的约定，这里保持一致。
     */
    private static List<ToolSpecification> stableOrder(Collection<ToolSpecification> specs) {
        List<ToolSpecification> out = new ArrayList<>(specs);
        out.sort(Comparator
                .comparingInt((ToolSpecification s) -> isMcpSpec(s) ? 1 : 0)
                .thenComparing(s -> Objects.toString(s.name(), ""), String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    private static boolean isMcpSpec(ToolSpecification spec) {
        String n = spec.name();
        return Objects.nonNull(n) && n.startsWith("mcp__");
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

    public Optional<ToolDescriptor> getDescriptor(String name) {
        Tool tool = tools.get(name);
        return tool == null ? Optional.empty() : Optional.of(tool.descriptor());
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
        if (Objects.nonNull(params) && !params.isEmpty()) {
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
                            if (StringUtils.isBlank(desc)) {
                                schema.addIntegerProperty(paramName);
                            } else {
                                schema.addIntegerProperty(paramName, desc);
                            }
                        }
                        case "number" -> {
                            if (StringUtils.isBlank(desc)) {
                                schema.addNumberProperty(paramName);
                            } else {
                                schema.addNumberProperty(paramName, desc);
                            }
                        }
                        case "boolean" -> {
                            if (StringUtils.isBlank(desc)) {
                                schema.addBooleanProperty(paramName);
                            } else {
                                schema.addBooleanProperty(paramName, desc);
                            }
                        }
                        default -> {
                            if (StringUtils.isBlank(desc)) {
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
