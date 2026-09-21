package com.miniagent.agent.execution;

import com.miniagent.agent.tool.Tool;
import com.miniagent.agent.tool.ToolConcurrencyPolicy;
import com.miniagent.agent.tool.ToolDescriptor;
import com.miniagent.agent.tool.ToolParams;
import com.miniagent.agent.tool.ToolRegistry;
import com.miniagent.agent.tool.ToolSideEffect;
import com.miniagent.agent.tool.impl.ExecCommandParams;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 执行闸门的参数感知行为。
 *
 * <p>这一层是「参数感知」真正生效的地方：{@code AgentLoop} 把参数交给它，并行判定与超时都从这里取。
 * 只改 {@code ToolConcurrencyPolicy} 而不改这里，编译能过、日志正常，
 * 只是新逻辑永远走不到 —— 所以这里必须有测试。</p>
 */
class ToolExecutionGuardsExecTest {

    /** 与 AgentLoop 反射取名字/参数的契约一致：{@code name()} / {@code arguments()}。 */
    private record Call(String name, String arguments) {
    }

    private static final Function<Object, String> NAME_OF = c -> ((Call) c).name();
    private static final Function<Object, String> ARGS_OF = c -> ((Call) c).arguments();

    private static ToolRegistry registry() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(Tool.builder()
                .name(ToolConcurrencyPolicy.EXEC_TOOL)
                .description("test exec")
                .parameters(ToolParams.generateSchema(ExecCommandParams.class))
                .sideEffect(ToolConcurrencyPolicy.sideEffectOf(ToolConcurrencyPolicy.EXEC_TOOL))
                .timeoutSeconds(ToolConcurrencyPolicy.timeoutSecondsOf(ToolConcurrencyPolicy.EXEC_TOOL))
                .adaptiveTimeoutSeconds(ExecCommandParams::outerGateSecondsOf)
                .handler(json -> "exit_code=0")
                .build());
        registry.register(Tool.builder()
                .name("read_file")
                .description("test read")
                .parameters(Map.of())
                .sideEffect(ToolSideEffect.READ_ONLY)
                .idempotent(true)
                .timeoutSeconds(10L)
                .handler(json -> "{}")
                .build());
        return registry;
    }

    private static List<Call> execBatch(String... commandJsons) {
        return Arrays.stream(commandJsons).map(c -> new Call("exec_command", c)).toList();
    }

    @Test
    void readOnlyExecBatchRunsInParallel() {
        ToolExecutionGuards guards = new ToolExecutionGuards(registry(), 8);

        assertTrue(guards.canRunBatchInParallel(
                execBatch("{\"command\":\"git status\"}", "{\"command\":\"git log --oneline -5\"}"),
                NAME_OF, ARGS_OF));
    }

    @Test
    void oneWritingExecCallForcesTheWholeBatchSerial() {
        ToolExecutionGuards guards = new ToolExecutionGuards(registry(), 8);

        assertFalse(guards.canRunBatchInParallel(
                execBatch("{\"command\":\"git status\"}", "{\"command\":\"del /q build\"}"),
                NAME_OF, ARGS_OF));
    }

    @Test
    void unknownToolForcesSerialSoErrorsSurfaceInOrder() {
        ToolExecutionGuards guards = new ToolExecutionGuards(registry(), 8);

        assertFalse(guards.canRunBatchInParallel(
                List.of(new Call("exec_command", "{\"command\":\"git status\"}"), new Call("nope", "{}")),
                NAME_OF, ARGS_OF));
    }

    @Test
    void singleCallNeverRunsInParallel() {
        ToolExecutionGuards guards = new ToolExecutionGuards(registry(), 8);

        assertFalse(guards.canRunBatchInParallel(
                execBatch("{\"command\":\"git status\"}"), NAME_OF, ARGS_OF));
    }

    @Test
    void outerGateReadsTheTimeoutFromArguments() {
        ToolExecutionGuards guards = new ToolExecutionGuards(registry(), 8);

        assertEquals(315L, guards.timeoutSeconds("exec_command", "{\"command\":\"mvnw package\",\"timeout\":300}"));
        assertEquals(615L, guards.timeoutSeconds("exec_command", "{\"command\":\"mvnw package\",\"timeout\":600}"));
        // 没给参数时退回默认闸门，绝不能是 0（future.get(0) 会立刻超时）
        assertEquals((long) ExecCommandParams.defaultOuterGateSeconds(), guards.timeoutSeconds("exec_command"));
    }

    @Test
    void descriptorForNonExecToolsIsPassedThroughUnchanged() {
        // 重建 record 会把 MCP 工具自己声明的 sideEffect 覆盖成默认值，那是静默回退
        ToolRegistry registry = registry();
        ToolExecutionGuards guards = new ToolExecutionGuards(registry, 8);
        ToolDescriptor registered = registry.getDescriptor("read_file").orElseThrow();

        assertEquals(registered, guards.descriptor("read_file", "{\"path\":\"a\"}"));
        assertEquals(ToolSideEffect.READ_ONLY, guards.descriptor("read_file", "{\"path\":\"a\"}").sideEffect());
    }

    @Test
    void descriptorForExecFollowsTheCommandLine() {
        ToolExecutionGuards guards = new ToolExecutionGuards(registry(), 8);

        assertEquals(ToolSideEffect.READ_ONLY, guards.descriptor("exec_command", "{\"command\":\"ls\"}").sideEffect());
        assertEquals(ToolSideEffect.EXTERNAL_WRITE,
                guards.descriptor("exec_command", "{\"command\":\"del /q build\"}").sideEffect());
    }
}
