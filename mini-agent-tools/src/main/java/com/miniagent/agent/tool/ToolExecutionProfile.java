package com.miniagent.agent.tool;

import java.util.Objects;

/**
 * 工具执行契约：一次声明「跑多久、抢什么资源、超时了怎么办」。
 *
 * <h2>为什么要有这个 record</h2>
 *
 * <p>改造前这四件事散在四处，加一个长耗时工具要同时改四处：</p>
 * <ul>
 *   <li><b>内层预算</b>（工具自己强杀）写在各工具参数类里 ——
 *       {@code ExecCommandParams.DEFAULT_TIMEOUT_SECONDS}、
 *       {@code SongGenerateParams.DEFAULT_POLL_BUDGET_SECONDS}……</li>
 *   <li><b>外层闸门</b>（{@code AgentLoop} 的 {@code future.get}）写在
 *       {@code ToolConcurrencyPolicy.timeoutSecondsOf} 的 switch 里，一堆魔法数；</li>
 *   <li><b>并发范围</b>写在 {@code ToolConcurrencyPolicy.concurrencyScopeOf} 的 Set 里；</li>
 *   <li><b>超时处置</b>写在 {@code AgentLoop.timeoutToolResult} 的 if-else 里。</li>
 * </ul>
 *
 * <p>漏掉超时那一处，工具就会在 60s 默认闸门被砍。现在四件事收敛到一个 record，
 * {@code ToolConcurrencyPolicy.profileOf(...)} 是<b>唯一的声明点</b>，
 * 注册期 / 闸门 / 超时恢复三处都从这里取。</p>
 *
 * <h2>三层预算的关系（这是整个设计的核心）</h2>
 *
 * <pre>
 *   锁等待 {@link #lockWaitSeconds()}         —— 拿不到锁就退（资源繁忙，可重试），绝不无限阻塞
 *   内层 {@link #executionBudgetSeconds()}   —— 工具自己强杀 + 返回部分输出（可控终态）
 *   外层 {@link #outerGateSeconds()}          —— AgentLoop 的 future.get，<b>必须比内层宽</b>
 * </pre>
 *
 * <p>外层一旦先触发，超时会被判成「终态未知」并中止整轮（见 {@code AgentLoop.timeoutToolResult}），
 * 而工具自己处理超时是可控终态。所以外层必须比内层宽，否则内部那套精细处理永远是死代码
 * —— 这正是改造前内外层同值时的后果。</p>
 *
 * <h2>预算可以随参数变化</h2>
 *
 * <p>{@code exec_command} 跑 {@code git status} 和跑 {@code mvnw package} 需要的预算差三个数量级，
 * 所以它的内层预算来自调用方声明的 {@code timeout} 参数（见 {@link #withBudget(long)}）。
 * 注册期只有工具名没有参数，「随调用变化的预算」只能由参数来算。</p>
 *
 * @param executionBudgetSeconds   内层预算（秒）：工具自己强杀并返回部分输出
 * @param outerGateMarginSeconds   外层闸门相对内层的余量（秒）：覆盖清理 / 落盘 / 读流
 * @param lockWaitSeconds          资源锁等待上限（秒）：到点退让，绝不无限阻塞
 * @param concurrencyScope         串行化范围：抢哪一类资源
 * @param concurrencyKeyArgument   {@code ARGUMENT} 范围的分锁参数键
 * @param sharedResourceKey        {@code SHARED_RESOURCE} 范围的共享资源名（如 {@code comfyui-gpu}）
 * @param timeoutRecovery          外层闸门超时后的处置策略
 * @param recoveryHint             超时后给模型的人话提示（含核验步骤 / 续查方式）
 * @param maxRetries               自动重试次数（&gt; 0 时工具必须幂等）
 */
public record ToolExecutionProfile(
        long executionBudgetSeconds,
        long outerGateMarginSeconds,
        long lockWaitSeconds,
        ToolConcurrencyScope concurrencyScope,
        String concurrencyKeyArgument,
        String sharedResourceKey,
        ToolTimeoutRecovery timeoutRecovery,
        String recoveryHint,
        int maxRetries
) {

    /** 未声明契约的工具（含 MCP 动态注册）用它兜底：60s 预算、全局互斥、超时即中止。 */
    public static final ToolExecutionProfile DEFAULT = new ToolExecutionProfile(
            60, 15, 10,
            ToolConcurrencyScope.GLOBAL, "", "",
            ToolTimeoutRecovery.ABORT,
            "，调用可能已产生副作用；必须先核验实际状态，再决定是否重试或补偿", 0);

    public ToolExecutionProfile {
        executionBudgetSeconds = executionBudgetSeconds > 0 ? executionBudgetSeconds : DEFAULT.executionBudgetSeconds;
        outerGateMarginSeconds = Math.max(0, outerGateMarginSeconds);
        lockWaitSeconds = lockWaitSeconds > 0 ? lockWaitSeconds : DEFAULT.lockWaitSeconds;
        concurrencyScope = Objects.requireNonNullElse(concurrencyScope, ToolConcurrencyScope.GLOBAL);
        concurrencyKeyArgument = Objects.requireNonNullElse(concurrencyKeyArgument, "").trim();
        sharedResourceKey = Objects.requireNonNullElse(sharedResourceKey, "").trim();
        timeoutRecovery = Objects.requireNonNullElse(timeoutRecovery, ToolTimeoutRecovery.ABORT);
        recoveryHint = Objects.requireNonNullElse(recoveryHint, "").trim();
        maxRetries = Math.max(0, maxRetries);
        if (concurrencyScope == ToolConcurrencyScope.ARGUMENT && concurrencyKeyArgument.isEmpty()) {
            throw new IllegalArgumentException("ARGUMENT 并发范围必须声明分锁参数键");
        }
        if (concurrencyScope == ToolConcurrencyScope.SHARED_RESOURCE && sharedResourceKey.isEmpty()) {
            throw new IllegalArgumentException("SHARED_RESOURCE 并发范围必须声明共享资源名");
        }
    }

    /** 外层闸门秒数 = 内层预算 + 余量。<b>永远要比内层宽</b>，理由见类注释。 */
    public long outerGateSeconds() {
        return executionBudgetSeconds + outerGateMarginSeconds;
    }

    /**
     * 换一个内层预算，其余契约不变。
     *
     * <p>供预算随参数变化的工具使用（{@code exec_command} 的 {@code timeout} 参数）：
     * 调用方声明跑多久，锁布局与超时处置仍按工具的静态契约走。</p>
     */
    public ToolExecutionProfile withBudget(long newExecutionBudgetSeconds) {
        return new ToolExecutionProfile(newExecutionBudgetSeconds, outerGateMarginSeconds,
                lockWaitSeconds, concurrencyScope, concurrencyKeyArgument, sharedResourceKey,
                timeoutRecovery, recoveryHint, maxRetries);
    }

    /** 换一个并发范围与分锁键，其余契约不变。供同族工具按只读性分流使用。 */
    public ToolExecutionProfile withConcurrency(
            ToolConcurrencyScope newScope, String newKeyArgument, String newSharedResourceKey) {
        return new ToolExecutionProfile(executionBudgetSeconds, outerGateMarginSeconds,
                lockWaitSeconds, newScope, newKeyArgument, newSharedResourceKey,
                timeoutRecovery, recoveryHint, maxRetries);
    }
}
