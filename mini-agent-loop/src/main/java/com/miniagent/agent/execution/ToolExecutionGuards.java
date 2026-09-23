package com.miniagent.agent.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.agent.tool.ToolConcurrencyPolicy;
import com.miniagent.agent.tool.ToolDescriptor;
import com.miniagent.agent.tool.ToolExecutionProfile;
import com.miniagent.agent.tool.ToolRegistry;
import com.miniagent.agent.tool.ToolSideEffect;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 全局有界并发加上固定条带资源锁。
 *
 * <h2>锁等待必须有界</h2>
 *
 * <p>改造前这里是 {@code Semaphore.acquire()}（无限阻塞）。长耗时工具持锁期间，
 * 短工具只能一路阻塞到撞自己的外层闸门，然后被判成「终态未知」并中止整轮。
 * 现在用 {@code tryAcquire(锁等待上限)}，到点抛 {@link ToolLockTimeoutException}
 * —— 工具<b>根本没跑</b>，无副作用，可安全重试。绝不让「没轮到」升级成「整轮中止」。</p>
 *
 * <h2>锁键按实际资源分开</h2>
 *
 * <p>改造前 {@code GLOBAL} 把本地文件系统、本地 GPU、远程 API 配额三类互不相干的资源
 * 挤到同一个 {@code "global"} 键上。现在 {@code SHARED_RESOURCE} 按命名资源分键
 * （{@code comfyui-gpu} / {@code image-api}），与本地文件系统互不干扰。</p>
 *
 * <h2>外层闸门的取值顺序</h2>
 *
 * <p>{@code exec_command} 的 timeout 参数（调用方按次声明）&gt;
 * {@code agent.tools.timeout-overrides} 配置覆盖（{@link AgentToolsProperties}）&gt;
 * 注册契约（{@code ToolExecutionProfile}，含注册期从配置派生的预算）。
 * 这里是 AgentLoop 取闸门的<b>唯一漏斗</b>，覆盖只改执行契约里的预算字段
 * （{@code withBudget}），锁布局与超时处置保持该工具的声明不变。</p>
 */
@Slf4j
@Component
public class ToolExecutionGuards implements SmartInitializingSingleton {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int LOCK_STRIPES = 128;
    private final ToolRegistry registry;
    private final AgentToolsProperties properties;
    private final Semaphore globalConcurrency;
    private final Semaphore[] resourceLocks = new Semaphore[LOCK_STRIPES];

    public ToolExecutionGuards(ToolRegistry registry, AgentToolsProperties properties) {
        this.registry = registry;
        this.properties = properties;
        this.globalConcurrency = new Semaphore(Math.max(1, properties.getMaxConcurrency()), true);
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
     * <p>只对 {@code exec_command} 重算：它的只读性/幂等性/并发布局/预算都随命令行变化。
     * 其他工具一律原样返回注册期契约 —— 重建 record 会把 MCP 工具自己声明的
     * {@code sideEffect} 覆盖成默认值（只读的 MCP 工具会被降级成写类，等于回退）。</p>
     *
     * <p>最后统一套配置覆盖（{@code agent.tools.timeout-overrides}）：覆盖只换预算，
     * 外层闸门精确等于配置值，锁与超时处置不动。{@code exec_command} 的覆盖条目
     * 在 {@link AgentToolsProperties#gateOverrideSeconds} 就被拒掉了。</p>
     */
    public ToolDescriptor descriptor(String name, String argumentsJson) {
        ToolDescriptor base = registry.getDescriptor(name).orElseGet(() -> new ToolDescriptor(
                name, "", Map.of(),
                ToolSideEffect.EXTERNAL_WRITE, false, false, false,
                ToolExecutionProfile.DEFAULT));
        ToolDescriptor resolved = !ToolConcurrencyPolicy.EXEC_TOOL.equals(name) ? base
                : new ToolDescriptor(base.name(), base.description(), base.parameters(),
                        ToolConcurrencyPolicy.sideEffectOf(name, argumentsJson),
                        ToolConcurrencyPolicy.isIdempotent(name, argumentsJson),
                        ToolConcurrencyPolicy.isStreamPrefetchSafe(name, argumentsJson),
                        base.cancellable(),
                        ToolConcurrencyPolicy.profileOf(name, argumentsJson));
        return applyTimeoutOverride(resolved);
    }

    /**
     * 把配置覆盖折进执行契约：值 = 外层闸门，内层预算 = 值 − 档位余量（不变式机械保持）。
     *
     * <p>{@code 值 ≤ 余量} 推不出正的内层预算，视为非法条目并忽略
     * （启动自检里已经告警过），回退注册契约。</p>
     */
    private ToolDescriptor applyTimeoutOverride(ToolDescriptor descriptor) {
        Long gate = properties.gateOverrideSeconds(descriptor.name());
        if (gate == null) {
            return descriptor;
        }
        ToolExecutionProfile profile = descriptor.executionProfile();
        if (gate <= profile.outerGateMarginSeconds()) {
            return descriptor;
        }
        return new ToolDescriptor(descriptor.name(), descriptor.description(), descriptor.parameters(),
                descriptor.sideEffect(), descriptor.idempotent(), descriptor.streamPrefetchSafe(),
                descriptor.cancellable(), profile.withBudget(gate - profile.outerGateMarginSeconds()));
    }

    /**
     * 启动自检：逐条核对 {@code agent.tools.timeout-overrides}，非法/无效条目告警并忽略。
     *
     * <p>「未注册工具」的告警跳过 {@code mcp__} 前缀名 —— MCP 工具在
     * {@code ApplicationReadyEvent} 之后才动态注册，启动这一刻查不到不等于配错。</p>
     */
    @Override
    public void afterSingletonsInstantiated() {
        Map<String, Long> overrides = properties.getTimeoutOverrides();
        if (overrides.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Long> entry : overrides.entrySet()) {
            String name = entry.getKey();
            Long value = entry.getValue();
            if (value == null || value <= 0) {
                log.warn("agent.tools.timeout-overrides 忽略非法值: {}={}（必须为正整数秒）", name, value);
                continue;
            }
            if (ToolConcurrencyPolicy.EXEC_TOOL.equals(name)) {
                log.warn("agent.tools.timeout-overrides 不接受 {} 的名字级覆盖（预算随每次调用的 timeout 参数变化），已忽略",
                        name);
                continue;
            }
            if (registry.getDescriptor(name).isEmpty() && !name.startsWith("mcp__")) {
                log.warn("agent.tools.timeout-overrides 指向未注册的工具: {}（拼错名字？MCP 工具请用 mcp__ 前缀名）", name);
            }
        }
    }

    /**
     * 在有界锁等待下执行工具。
     *
     * <p>两段锁（先全局并发额度、再资源锁）都用 {@code tryAcquire}，到点抛
     * {@link ToolLockTimeoutException} 退让。释放顺序与获取顺序相反，
     * 且只有真正拿到的锁才释放 —— 中途退让不会把没拿的锁放掉。</p>
     */
    public <T> T executeGuarded(String toolName, String args, String sessionId, Callable<T> operation)
            throws Exception {
        ToolDescriptor descriptor = descriptor(toolName, args);
        String resourceKey = resourceKey(descriptor, args, sessionId);
        Semaphore resource = resourceKey.isEmpty() ? null
                : resourceLocks[Math.floorMod(resourceKey.hashCode(), resourceLocks.length)];
        long lockWaitSeconds = descriptor.lockWaitSeconds();

        if (!globalConcurrency.tryAcquire(lockWaitSeconds, TimeUnit.SECONDS)) {
            throw new ToolLockTimeoutException(toolName, "全局并发额度", lockWaitSeconds);
        }
        boolean resourceAcquired = false;
        try {
            if (resource != null) {
                if (!resource.tryAcquire(lockWaitSeconds, TimeUnit.SECONDS)) {
                    throw new ToolLockTimeoutException(toolName, "资源锁 " + resourceKey, lockWaitSeconds);
                }
                resourceAcquired = true;
            }
            return operation.call();
        } finally {
            if (resourceAcquired) {
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
            // 命名的外部共享资源：comfyui_* 抢同一块 GPU、生成 API 抢同一份配额。
            // 键写在执行契约里，与本地文件系统的 "global" 键互不干扰。
            case SHARED_RESOURCE -> descriptor.sharedResourceKey();
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
