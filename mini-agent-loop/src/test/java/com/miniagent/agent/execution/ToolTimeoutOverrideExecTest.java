package com.miniagent.agent.execution;

import com.miniagent.agent.tool.Tool;
import com.miniagent.agent.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@code agent.tools.timeout-overrides} 的生效规则：
 * 值 = 外层闸门（所见即所得），内层预算 = 值 − 档位余量（不变式机械保持），
 * 非法条目与 exec_command 一律忽略并回退注册契约。
 */
class ToolTimeoutOverrideExecTest {

    private static ToolRegistry registryWith(String name) {
        ToolRegistry registry = new ToolRegistry();
        // 走便捷注册重载：与生产注册同路径（自动挂 ToolConcurrencyPolicy.profileOf 的执行契约）
        registry.register(name, "test tool", Map.of(), args -> "ok");
        return registry;
    }

    private static AgentToolsProperties props(Map<String, Long> overrides) {
        AgentToolsProperties properties = new AgentToolsProperties();
        properties.setTimeoutOverrides(overrides);
        return properties;
    }

    @Test
    void overrideWinsOverTheRegisteredContract() {
        // read_file 注册契约外层闸门 10s（READ_BY_PATH.withBudget(10)），覆盖成 30s
        ToolExecutionGuards guards = new ToolExecutionGuards(registryWith("read_file"),
                props(Map.of("read_file", 30L)));
        assertEquals(30L, guards.timeoutSeconds("read_file"));
        assertEquals(30L, guards.descriptor("read_file").executionBudgetSeconds(),
                "余量 0 的档位：内层预算 = 外层闸门");
    }

    @Test
    void overrideOnATooledMarginSubtractsTheMargin() {
        // 未注册工具落到 DEFAULT 档（余量 15s）：值 = 外层闸门，内层 = 值 − 15
        ToolExecutionGuards guards = new ToolExecutionGuards(registryWith("whatever"),
                props(Map.of("mcp__x__y", 42L)));
        assertEquals(42L, guards.timeoutSeconds("mcp__x__y"));
        assertEquals(27L, guards.descriptor("mcp__x__y").executionBudgetSeconds());
    }

    @Test
    void execCommandOverrideIsRejected() {
        // exec_command 预算随每次调用的 timeout 参数变化（300 + 15 = 315），名字级覆盖必须无效
        ToolExecutionGuards guards = new ToolExecutionGuards(registryWith("exec_command"),
                props(Map.of("exec_command", 999L)));
        assertEquals(315L, guards.timeoutSeconds("exec_command",
                "{\"command\":\"mvnw package\",\"timeout\":300}"));
    }

    @Test
    void nonPositiveValuesAreIgnored() {
        AgentToolsProperties properties = props(Map.of("read_file", 0L));
        assertNull(properties.gateOverrideSeconds("read_file"));
        properties.setTimeoutOverrides(Map.of("read_file", -5L));
        assertNull(properties.gateOverrideSeconds("read_file"));

        ToolExecutionGuards guards = new ToolExecutionGuards(registryWith("read_file"), properties);
        assertEquals(10L, guards.timeoutSeconds("read_file"), "非法覆盖回退注册契约");
    }

    @Test
    void missingOverrideKeepsTheRegisteredContract() {
        ToolExecutionGuards guards = new ToolExecutionGuards(registryWith("read_file"), props(Map.of()));
        assertEquals(10L, guards.timeoutSeconds("read_file"));
        assertNull(new AgentToolsProperties().gateOverrideSeconds("read_file"));
    }

    @Test
    void overrideNeverChangesLockLayoutOrTimeoutRecovery() {
        ToolExecutionGuards guards = new ToolExecutionGuards(registryWith("read_file"),
                props(Map.of("read_file", 30L)));
        var descriptor = guards.descriptor("read_file");
        assertEquals(3, descriptor.lockWaitSeconds(), "覆盖只换预算，锁等待不动");
        assertEquals(0, descriptor.maxRetries(), "覆盖只换预算，重试契约不动");
    }
}
