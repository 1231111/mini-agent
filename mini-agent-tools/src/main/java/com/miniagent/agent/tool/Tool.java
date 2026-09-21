package com.miniagent.agent.tool;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.Map;
import java.util.function.Function;
import java.util.Objects;

/**
 * 工具抽象：一个可被 Agent 调用的外部能力。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class Tool {

    private String name;
    private String description;
    /** JSON Schema 格式的参数描述 */
    @Builder.Default
    private Map<String, Object> parameters = Collections.emptyMap();
    @Builder.Default
    private ToolSideEffect sideEffect = ToolSideEffect.EXTERNAL_WRITE;
    @Builder.Default
    private boolean idempotent = false;
    @Builder.Default
    private boolean streamPrefetchSafe = false;
    @Builder.Default
    private boolean cancellable = false;
    @Builder.Default
    private long timeoutSeconds = 60L;
    /**
     * 按本次参数求超时的函数（返回 0 表示回退到 {@link #timeoutSeconds}）。
     *
     * <p>为什么超时不能只在注册期定死：{@code exec_command} 跑 {@code git status} 和跑
     * {@code mvnw package} 需要的预算差三个数量级。注册只有工具名，没有参数，
     * 所以「随调用变化的超时」只能由参数来算。</p>
     */
    private Function<String, Long> adaptiveTimeoutSeconds;
    @Builder.Default
    private int maxRetries = 0;
    @Builder.Default
    private ToolConcurrencyScope concurrencyScope = ToolConcurrencyScope.GLOBAL;
    @Builder.Default
    private String concurrencyKeyArgument = "";
    /** 执行函数：接收参数 JSON 字符串，返回结果字符串 */
    private Function<String, String> handler;
    /** 新执行器优先使用的结构化 handler；旧字符串 handler 继续兼容。 */
    private Function<String, ToolResult> resultHandler;

    /**
     * 执行工具调用
     * @param argumentsJson LLM 返回的参数 JSON
     * @return 执行结果
     */
    public String execute(String argumentsJson) {
        return executeResult(argumentsJson).legacyText();
    }

    public ToolResult executeResult(String argumentsJson) {
        ToolArgumentValidator.ValidationResult validation =
                ToolArgumentValidator.validate(parameters, argumentsJson);
        if (!validation.valid()) {
            return validation.error();
        }
        if (Objects.isNull(handler) && Objects.isNull(resultHandler)) {
            return ToolResult.failure(ToolErrorCode.INTERNAL_ERROR,
                    "工具 " + name + " 未配置执行器", false);
        }
        try {
            if (Objects.nonNull(resultHandler)) {
                ToolResult result = resultHandler.apply(argumentsJson);
                return Objects.nonNull(result) ? result
                        : ToolResult.failure(ToolErrorCode.EMPTY_RESULT, "工具返回空结果", false);
            }
            return ToolResult.fromLegacy(handler.apply(argumentsJson));
        } catch (Exception e) {
            return ToolResult.failure(ToolErrorCode.EXECUTION_FAILED,
                    "工具执行错误 [" + name + "]: " + e.getMessage(), false);
        }
    }

    public ToolDescriptor descriptor() {
        return new ToolDescriptor(name, description, parameters, sideEffect, idempotent,
                streamPrefetchSafe, cancellable, timeoutSeconds, maxRetries,
                concurrencyScope, concurrencyKeyArgument);
    }
}
