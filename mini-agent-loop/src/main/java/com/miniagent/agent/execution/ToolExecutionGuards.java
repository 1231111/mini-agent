package com.miniagent.agent.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.agent.tool.ToolConcurrencyPolicy;
import com.miniagent.agent.tool.ToolConcurrencyScope;
import com.miniagent.agent.tool.ToolDescriptor;
import com.miniagent.agent.tool.ToolRegistry;
import com.miniagent.agent.tool.ToolSideEffect;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.Semaphore;

/** 全局有界并发加上固定条带资源锁。 */
@Component
public class ToolExecutionGuards {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int LOCK_STRIPES = 128;
    private final ToolRegistry registry;
    private final Semaphore globalConcurrency;
    private final Semaphore[] resourceLocks = new Semaphore[LOCK_STRIPES];

    public ToolExecutionGuards(ToolRegistry registry,
                               @Value("${agent.tools.max-concurrency:8}") int maxConcurrency) {
        this.registry = registry;
        this.globalConcurrency = new Semaphore(Math.max(1, maxConcurrency), true);
        for (int i = 0; i < resourceLocks.length; i++) {
            resourceLocks[i] = new Semaphore(1, true);
        }
    }

    /**
     * 整批能否并行：要求每个调用都「只读且幂等」，或有对应的安全核验手段。
     *
     * <p>判定要吃<b>参数</b>：{@code exec_command} 的只读性取决于命令行内容
     * （{@code git status} 只读，{@code format C:} 不是），只看工具名会把它一律挡在并行之外。</p>
     */
    public boolean canRunBatchInParallel(List<?> calls,
                                         java.util.function.Function<Object, String> nameOf,
                                         java.util.function.Function<Object, String> argsOf) {
        if (calls == null || calls.size() < 2) {
            return false;
        }
        for (Object call : calls) {
            String name = nameOf.apply(call);
            if (registry.getDescriptor(name).isEmpty()) {
                // 未注册的工具（拼错名字、MCP 刚断开）一律串行，让错误按顺序暴露
                return false;
            }
            String args = argsOf == null ? null : argsOf.apply(call);
            ToolDescriptor descriptor = descriptor(name, args);
            if (descriptor.sideEffect() != ToolSideEffect.READ_ONLY || !descriptor.idempotent()) {
                return false;
            }
        }
        return true;
    }

    public long timeoutSeconds(String toolName) {
        return timeoutSeconds(toolName, null);
    }

    /** 按本次参数取外层闸门秒数（exec_command 的预算随 timeout 参数变化）。 */
    public long timeoutSeconds(String toolName, String argumentsJson) {
        return descriptor(toolName, argumentsJson).timeoutSeconds();
    }

    public ToolDescriptor descriptor(String name) {
        return descriptor(name, null);
    }

    /**
     * 参数感知的执行契约。
     *
     * <p>只对 {@code exec_command} 重算：它的只读性/幂等性/并发布局/超时都随命令行变化。
     * 其他工具一律原样返回注册期契约 —— 重建 record 会把 MCP 工具自己声明的
     * {@code sideEffect} 覆盖成默认值（只读的 MCP 工具会被降级成写类，等于回退）。</p>
     */
    public ToolDescriptor descriptor(String name, String argumentsJson) {
        ToolDescriptor base = registry.getDescriptor(name).orElseGet(() -> new ToolDescriptor(
                name, "", Map.of(),
                ToolSideEffect.EXTERNAL_WRITE, false, false, false, 60, 0,
                ToolConcurrencyScope.GLOBAL, ""));
        if (!ToolConcurrencyPolicy.EXEC_TOOL.equals(name)) {
            return base;
        }
        return new ToolDescriptor(base.name(), base.description(), base.parameters(),
                ToolConcurrencyPolicy.sideEffectOf(name, argumentsJson),
                ToolConcurrencyPolicy.isIdempotent(name, argumentsJson),
                ToolConcurrencyPolicy.isStreamPrefetchSafe(name, argumentsJson),
                base.cancellable(),
                ToolConcurrencyPolicy.timeoutSecondsOf(name, argumentsJson),
                base.maxRetries(),
                ToolConcurrencyPolicy.concurrencyScopeOf(name, argumentsJson),
                ToolConcurrencyPolicy.concurrencyKeyArgumentOf(name, argumentsJson));
    }

    public <T> T executeGuarded(String toolName, String args, String sessionId, Callable<T> operation) throws Exception {
        ToolDescriptor descriptor = descriptor(toolName, args);
        String resourceKey = resourceKey(descriptor, args, sessionId);
        Semaphore resource = resourceKey.isEmpty() ? null
                : resourceLocks[Math.floorMod(resourceKey.hashCode(), resourceLocks.length)];
        globalConcurrency.acquire();
        if (resource != null) {
            resource.acquire();
        }
        try { return operation.call(); }
        finally {
            if (resource != null) {
                resource.release();
            }
            globalConcurrency.release();
        }
    }

    static String resourceKey(ToolDescriptor descriptor, String args, String sessionId) {
        return switch (descriptor.concurrencyScope()) {
            case NONE -> "";
            case GLOBAL -> "global";
            case SESSION -> "session:" + Optional.ofNullable(sessionId).orElse("anonymous");
            case ARGUMENT -> descriptor.name() + ":" + argumentValue(args, descriptor.concurrencyKeyArgument());
        };
    }

    private static String argumentValue(String args, String key) {
        try {
            JsonNode node = JSON.readTree(args == null || args.isBlank() ? "{}" : args);
            JsonNode value = node == null ? null : node.get(key);
            return value == null || value.isNull() || value.asText().isBlank() ? "missing" : value.asText();
        } catch (Exception ignored) { return "missing"; }
    }
}
