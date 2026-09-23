package com.miniagent.agent.tool;

import java.util.Map;
import java.util.Objects;

/**
 * 工具的静态执行契约。
 *
 * <p>执行相关的四要素（内层预算 / 外层闸门 / 并发范围 / 超时处置）全部收进
 * {@link ToolExecutionProfile}，由 {@code ToolConcurrencyPolicy.profileOf(...)} 一处声明。
 * 这里的 {@code timeoutSeconds()} 等访问器是派生视图，签名与改造前一致，
 * 调用方（{@code AgentLoop} / {@code ToolExecutionGuards} / {@code ToolPipeline}）不需要跟着改。</p>
 */
public record ToolDescriptor(
        String name,
        String description,
        Map<String, Object> parameters,
        ToolSideEffect sideEffect,
        boolean idempotent,
        boolean streamPrefetchSafe,
        boolean cancellable,
        ToolExecutionProfile executionProfile
) {
    public ToolDescriptor {
        name = Objects.requireNonNullElse(name, "").trim();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("工具名称不能为空");
        }
        description = Objects.requireNonNullElse(description, "").trim();
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
        sideEffect = sideEffect == null ? ToolSideEffect.EXTERNAL_WRITE : sideEffect;
        executionProfile = executionProfile == null ? ToolExecutionProfile.DEFAULT : executionProfile;
        if (streamPrefetchSafe && (sideEffect != ToolSideEffect.READ_ONLY || !idempotent)) {
            throw new IllegalArgumentException("流式预执行工具必须只读且幂等: " + name);
        }
        if (executionProfile.maxRetries() > 0 && !idempotent) {
            throw new IllegalArgumentException("自动重试工具必须声明幂等: " + name);
        }
    }

    // ─── 派生自 executionProfile 的执行契约视图 ───

    /** 外层闸门秒数（{@code AgentLoop} 的 {@code future.get} 预算）。永远比内层宽。 */
    public long timeoutSeconds() {
        return executionProfile.outerGateSeconds();
    }

    /** 内层预算（秒）：工具自己强杀并返回部分输出的时点。 */
    public long executionBudgetSeconds() {
        return executionProfile.executionBudgetSeconds();
    }

    /** 资源锁等待上限（秒）：到点退让并报「资源繁忙」，绝不无限阻塞。 */
    public long lockWaitSeconds() {
        return executionProfile.lockWaitSeconds();
    }

    /** 自动重试次数。&gt; 0 时工具必须幂等（见下方紧凑构造器的校验）。 */
    public int maxRetries() {
        return executionProfile.maxRetries();
    }

    /** 串行化范围：抢哪一类共享资源。 */
    public ToolConcurrencyScope concurrencyScope() {
        return executionProfile.concurrencyScope();
    }

    /** {@code ARGUMENT} 范围的分锁参数键。 */
    public String concurrencyKeyArgument() {
        return executionProfile.concurrencyKeyArgument();
    }

    /** {@code SHARED_RESOURCE} 范围的共享资源名（如 {@code comfyui-gpu}）。 */
    public String sharedResourceKey() {
        return executionProfile.sharedResourceKey();
    }

    /** 外层闸门超时后的处置策略。 */
    public ToolTimeoutRecovery timeoutRecovery() {
        return executionProfile.timeoutRecovery();
    }

    /** 超时后给模型的人话提示（含核验步骤 / 续查方式）。 */
    public String recoveryHint() {
        return executionProfile.recoveryHint();
    }
}
