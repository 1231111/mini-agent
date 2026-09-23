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
 *
 * <p>执行契约（预算 / 闸门 / 锁 / 超时处置）集中在 {@link ToolExecutionProfile}，
 * 由 {@code ToolConcurrencyPolicy.profileOf(...)} 一处声明。下面的
 * {@code getTimeoutSeconds()} 等派生视图保持旧签名，调用方不必跟着改。</p>
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
    /** 执行契约：预算 / 闸门 / 锁等待 / 并发范围 / 超时处置，一次声明。 */
    @Builder.Default
    private ToolExecutionProfile executionProfile = ToolExecutionProfile.DEFAULT;

    /**
     * 按本次参数求外层闸门的函数（返回 0 或 null 表示回退到 {@link #executionProfile}）。
     *
     * <p>为什么闸门不能只在注册期定死：{@code exec_command} 跑 {@code git status} 和跑
     * {@code mvnw package} 需要的预算差三个数量级。注册只有工具名，没有参数，
     * 所以「随调用变化的闸门」只能由参数来算。</p>
     */
    private Function<String, Long> adaptiveTimeoutSeconds;
    /** 执行函数：接收参数 JSON 字符串，返回结果字符串 */
    private Function<String, String> handler;
    /** 新执行器优先使用的结构化 handler；旧字符串 handler 继续兼容。 */
    private Function<String, ToolResult> resultHandler;

    // ─── 派生自 executionProfile 的执行契约视图（保持旧签名） ───

    /** 外层闸门秒数。随参数变化时看 {@link #adaptiveTimeoutSeconds}。 */
    public long getTimeoutSeconds() {
        return executionProfile.outerGateSeconds();
    }

    /** 自动重试次数。 */
    public int getMaxRetries() {
        return executionProfile.maxRetries();
    }

    /** 串行化范围。 */
    public ToolConcurrencyScope getConcurrencyScope() {
        return executionProfile.concurrencyScope();
    }

    /** {@code ARGUMENT} 范围的分锁参数键。 */
    public String getConcurrencyKeyArgument() {
        return executionProfile.concurrencyKeyArgument();
    }

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
                streamPrefetchSafe, cancellable, executionProfile);
    }
}
