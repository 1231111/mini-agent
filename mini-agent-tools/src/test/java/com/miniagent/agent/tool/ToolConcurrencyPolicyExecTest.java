package com.miniagent.agent.tool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 参数感知的执行策略。
 *
 * <p>改造前这些判定<b>只吃工具名</b>，于是 {@code exec_command} 无论跑什么都是「写类 + 30 秒」：
 * 跑 {@code git status} 进不了并行批，跑 {@code mvnw package} 必然超时，
 * 而超时在写类工具上会升级成「终态未知」→ 整轮中止。这里锁住修复后的对应关系。</p>
 */
class ToolConcurrencyPolicyExecTest {

    private static final String READ_ONLY_ARGS = "{\"command\":\"git status\"}";
    private static final String WRITE_ARGS = "{\"command\":\"del /q build\"}";

    @Test
    void execIsReadOnlyOnlyWhenTheCommandIs() {
        assertEquals(ToolSideEffect.READ_ONLY, ToolConcurrencyPolicy.sideEffectOf("exec_command", READ_ONLY_ARGS));
        assertEquals(ToolSideEffect.EXTERNAL_WRITE, ToolConcurrencyPolicy.sideEffectOf("exec_command", WRITE_ARGS));

        assertTrue(ToolConcurrencyPolicy.isReadOnly("exec_command", READ_ONLY_ARGS));
        assertFalse(ToolConcurrencyPolicy.isReadOnly("exec_command", WRITE_ARGS));
    }

    @Test
    void unparsableArgumentsAreTreatedAsWrite() {
        // 拿不准一律按最严处理：误判成只读会让写类命令被当成可安全重试
        assertEquals(ToolSideEffect.EXTERNAL_WRITE, ToolConcurrencyPolicy.sideEffectOf("exec_command", null));
        assertEquals(ToolSideEffect.EXTERNAL_WRITE, ToolConcurrencyPolicy.sideEffectOf("exec_command", "{bad"));
        assertEquals(ToolSideEffect.EXTERNAL_WRITE, ToolConcurrencyPolicy.sideEffectOf("exec_command", "{}"));
    }

    @Test
    void readOnlyExecGetsItsOwnLockInsteadOfTheGlobalOne() {
        // 只读命令按命令行文本分锁：不同命令可以同时跑
        assertEquals(ToolConcurrencyScope.ARGUMENT, ToolConcurrencyPolicy.concurrencyScopeOf("exec_command", READ_ONLY_ARGS));
        assertEquals("command", ToolConcurrencyPolicy.concurrencyKeyArgumentOf("exec_command", READ_ONLY_ARGS));
        // 写类命令维持 GLOBAL，与写文件等共用一把锁
        assertEquals(ToolConcurrencyScope.GLOBAL, ToolConcurrencyPolicy.concurrencyScopeOf("exec_command", WRITE_ARGS));
    }

    @Test
    void readOnlyExecIsIdempotentButNeverPrefetched() {
        assertTrue(ToolConcurrencyPolicy.isIdempotent("exec_command", READ_ONLY_ARGS));
        assertFalse(ToolConcurrencyPolicy.isIdempotent("exec_command", WRITE_ARGS));
        // 预取会在模型意图确定前就起进程，判断失误就是一个白跑的子进程
        assertFalse(ToolConcurrencyPolicy.isStreamPrefetchSafe("exec_command", READ_ONLY_ARGS));
    }

    @Test
    void timeoutFollowsTheDeclaredParameter() {
        assertEquals(315L, ToolConcurrencyPolicy.timeoutSecondsOf("exec_command",
                "{\"command\":\"mvnw package\",\"timeout\":300}"));
        assertEquals(615L, ToolConcurrencyPolicy.timeoutSecondsOf("exec_command",
                "{\"command\":\"mvnw package\",\"timeout\":600}"));
        assertEquals((long) com.miniagent.agent.tool.impl.ExecCommandParams.defaultOuterGateSeconds(),
                ToolConcurrencyPolicy.timeoutSecondsOf("exec_command", READ_ONLY_ARGS),
                "没声明超时就用默认闸门");
    }

    @Test
    void brokenArgumentsFallBackToTheStaticValueInsteadOfZero() {
        // 闸门取到 0 会让 future.get(0) 立刻超时 —— 必须回退到静态兜底
        long gate = ToolConcurrencyPolicy.timeoutSecondsOf("exec_command", "{bad json");
        assertEquals((long) com.miniagent.agent.tool.impl.ExecCommandParams.defaultOuterGateSeconds(), gate);
        assertTrue(gate > 0);
    }

    @Test
    void otherToolsAreUnaffectedByTheParameterAwareEntryPoints() {
        // 非 exec 的工具没有参数感知语义，必须与旧行为逐项一致，否则是静默回退
        assertEquals(ToolConcurrencyPolicy.sideEffectOf("read_file"), ToolConcurrencyPolicy.sideEffectOf("read_file", "{\"path\":\"a\"}"));
        assertEquals(ToolConcurrencyPolicy.sideEffectOf("write_file"), ToolConcurrencyPolicy.sideEffectOf("write_file", "{\"path\":\"a\"}"));
        assertEquals(ToolConcurrencyPolicy.timeoutSecondsOf("read_file"), ToolConcurrencyPolicy.timeoutSecondsOf("read_file", "{\"path\":\"a\"}"));
        assertEquals(ToolConcurrencyPolicy.concurrencyScopeOf("read_file"), ToolConcurrencyPolicy.concurrencyScopeOf("read_file", "{\"path\":\"a\"}"));
        assertTrue(ToolConcurrencyPolicy.isStreamPrefetchSafe("search_code", "{\"query\":\"x\"}"));
    }
}
