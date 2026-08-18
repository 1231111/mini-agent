package com.miniagent.agent.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
        for (int i = 0; i < resourceLocks.length; i++) resourceLocks[i] = new Semaphore(1, true);
    }

    public boolean canRunBatchInParallel(List<?> calls, java.util.function.Function<Object, String> nameOf) {
        if (calls == null || calls.size() < 2) return false;
        return calls.stream().allMatch(call -> registry.getDescriptor(nameOf.apply(call))
                .filter(d -> d.sideEffect() == ToolSideEffect.READ_ONLY && d.idempotent()).isPresent());
    }

    public long timeoutSeconds(String toolName) { return descriptor(toolName).timeoutSeconds(); }
    public ToolDescriptor descriptor(String name) {
        return registry.getDescriptor(name).orElseGet(() -> new ToolDescriptor(name, "", Map.of(),
                ToolSideEffect.EXTERNAL_WRITE, false, false, false, 60, 0,
                ToolConcurrencyScope.GLOBAL, ""));
    }

    public <T> T executeGuarded(String toolName, String args, String sessionId, Callable<T> operation) throws Exception {
        ToolDescriptor descriptor = descriptor(toolName);
        String resourceKey = resourceKey(descriptor, args, sessionId);
        Semaphore resource = resourceKey.isEmpty() ? null
                : resourceLocks[Math.floorMod(resourceKey.hashCode(), resourceLocks.length)];
        globalConcurrency.acquire();
        if (resource != null) resource.acquire();
        try { return operation.call(); }
        finally {
            if (resource != null) resource.release();
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
