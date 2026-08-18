package com.miniagent.agent.tool;

import java.util.Map;
import java.util.Objects;

/** 工具的静态执行契约。 */
public record ToolDescriptor(
        String name,
        String description,
        Map<String, Object> parameters,
        ToolSideEffect sideEffect,
        boolean idempotent,
        boolean streamPrefetchSafe,
        boolean cancellable,
        long timeoutSeconds,
        int maxRetries,
        ToolConcurrencyScope concurrencyScope,
        String concurrencyKeyArgument
) {
    public ToolDescriptor {
        name = Objects.requireNonNullElse(name, "").trim();
        if (name.isEmpty()) throw new IllegalArgumentException("工具名称不能为空");
        description = Objects.requireNonNullElse(description, "").trim();
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
        sideEffect = sideEffect == null ? ToolSideEffect.EXTERNAL_WRITE : sideEffect;
        timeoutSeconds = timeoutSeconds > 0 ? timeoutSeconds : 60L;
        maxRetries = Math.max(0, maxRetries);
        concurrencyScope = concurrencyScope == null ? ToolConcurrencyScope.GLOBAL : concurrencyScope;
        concurrencyKeyArgument = Objects.requireNonNullElse(concurrencyKeyArgument, "").trim();
        if (streamPrefetchSafe && (sideEffect != ToolSideEffect.READ_ONLY || !idempotent)) {
            throw new IllegalArgumentException("流式预执行工具必须只读且幂等: " + name);
        }
        if (maxRetries > 0 && !idempotent) {
            throw new IllegalArgumentException("自动重试工具必须声明幂等: " + name);
        }
        if (concurrencyScope == ToolConcurrencyScope.ARGUMENT && concurrencyKeyArgument.isEmpty()) {
            throw new IllegalArgumentException("ARGUMENT 并发范围必须声明参数键: " + name);
        }
    }
}
