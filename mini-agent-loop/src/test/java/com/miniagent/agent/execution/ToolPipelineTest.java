package com.miniagent.agent.execution;

import com.miniagent.agent.core.ExecutionControl;
import com.miniagent.agent.core.ExecutionTurnContext;
import com.miniagent.agent.core.LoopTurnContext;
import com.miniagent.agent.core.LoopTurnPolicy;
import com.miniagent.agent.core.RunScope;
import com.miniagent.agent.hook.ToolHookChain;
import com.miniagent.agent.permission.ExecPolicyService;
import com.miniagent.agent.permission.PermissionContext;
import com.miniagent.agent.permission.PermissionMode;
import com.miniagent.agent.permission.SessionPermissionStore;
import com.miniagent.agent.tool.ToolErrorCode;
import com.miniagent.agent.tool.ToolRegistry;
import com.miniagent.agent.tool.ToolResult;
import com.miniagent.agent.trace.TraceRecorder;
import com.miniagent.common.permission.ExecPolicy;
import com.miniagent.config.entity.AgentTraceStep;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolPipelineTest {

    @AfterEach
    void tearDown() {
        LoopTurnContext.clear();
        ExecutionTurnContext.clear();
        PermissionContext.clear();
    }

    @Test
    void invokeRunsRegisteredToolThroughJournal() {
        ToolRegistry registry = new ToolRegistry();
        AtomicInteger calls = new AtomicInteger();
        registry.register("write_file", "w", Map.of(), x -> {
            calls.incrementAndGet();
            return "{\"ok\":true}";
        });
        ToolPipeline pipeline = pipeline(registry);

        ToolInvocation first = pipeline.invoke(ToolRequest.of("s", "write_file", "{}", 0, "r1"));
        assertEquals(ToolInvocation.Outcome.EXECUTED, first.outcome());
        assertTrue(first.result().isSuccess());
        assertEquals(1, calls.get());

        ToolInvocation again = pipeline.invoke(ToolRequest.of("s", "write_file", "{}", 0, "r1"));
        assertTrue(again.result().isSuccess());
        assertTrue(again.text().contains("deduplicated"));
        assertEquals(1, calls.get());
    }

    @Test
    void hardGateDoesNotExecuteTool() {
        ToolRegistry registry = new ToolRegistry();
        AtomicInteger calls = new AtomicInteger();
        registry.register("exec_command", "e", Map.of(), x -> {
            calls.incrementAndGet();
            return "{\"ok\":true}";
        });
        LoopTurnContext.set(new HardGate(List.of("write_file")));
        ToolInvocation inv = pipeline(registry).invoke(
                ToolRequest.of("s", "exec_command", "{}", 0, "r1"));
        assertEquals(ToolInvocation.Outcome.GATE_DENIED, inv.outcome());
        assertEquals(0, calls.get());
    }

    @Test
    void toolOutsideTurnSurfaceNeverReachesRegistry() {
        ToolRegistry registry = new ToolRegistry();
        AtomicInteger calls = new AtomicInteger();
        registry.register("exec_command", "e", Map.of(), x -> {
            calls.incrementAndGet();
            return "{\"ok\":true}";
        });

        ToolInvocation inv = pipeline(registry).invoke(
                ToolRequest.of("s", "exec_command", "{}", 0, "r1")
                        .withAllowedTools(Set.of("read_file")));

        assertEquals(ToolInvocation.Outcome.POLICY_DENIED, inv.outcome());
        assertEquals(ToolErrorCode.PERMISSION_DENIED, inv.result().errorCode());
        assertEquals(0, calls.get());
    }

    @Test
    void disabledBrowserEvaluateNeverReachesRegistry() {
        ToolRegistry registry = new ToolRegistry();
        AtomicInteger calls = new AtomicInteger();
        registry.register("browser_evaluate", "e", Map.of(), x -> {
            calls.incrementAndGet();
            return "{\"ok\":true}";
        });

        ToolInvocation inv = pipeline(registry).invoke(
                ToolRequest.of("s", "browser_evaluate", "{}", 0, "r1")
                        .withAllowedTools(Set.of("browser_evaluate")));

        assertEquals(ToolInvocation.Outcome.POLICY_DENIED, inv.outcome());
        assertEquals(ToolErrorCode.PERMISSION_DENIED, inv.result().errorCode());
        assertEquals(0, calls.get());
    }

    @Test
    void registeredToolInheritsFrozenParentSurface() {
        ToolRegistry registry = new ToolRegistry();
        AtomicReference<Set<String>> inherited = new AtomicReference<>();
        registry.register("read_file", "r", Map.of(), x -> {
            inherited.set(ToolCallContext.allowedTools());
            return "{\"ok\":true}";
        });

        ToolInvocation inv = pipeline(registry).invoke(
                ToolRequest.of("s", "read_file", "{}", 0, "r1")
                        .withAllowedTools(Set.of("read_file")));

        assertEquals(ToolInvocation.Outcome.EXECUTED, inv.outcome());
        assertEquals(Set.of("read_file"), inherited.get());
        assertNull(ToolCallContext.allowedTools());
    }

    @Test
    void hardGateFollowsCapturedScopeAfterThreadLocalCleared() {
        ToolRegistry registry = new ToolRegistry();
        AtomicInteger calls = new AtomicInteger();
        registry.register("exec_command", "e", Map.of(), x -> {
            calls.incrementAndGet();
            return "{\"ok\":true}";
        });
        LoopTurnContext.set(new HardGate(List.of("write_file")));
        RunScope scope = RunScope.capture().withSession("s");
        LoopTurnContext.clear();
        ToolInvocation inv = pipeline(registry).invoke(
                ToolRequest.of(scope, "exec_command", "{}", 0, "r1"));
        assertEquals(ToolInvocation.Outcome.GATE_DENIED, inv.outcome());
        assertEquals(0, calls.get());
    }

    @Test
    void planDenyFollowsCapturedScopeAfterThreadLocalCleared() {
        ToolRegistry registry = new ToolRegistry();
        AtomicInteger calls = new AtomicInteger();
        registry.register("write_file", "w", Map.of(), x -> {
            calls.incrementAndGet();
            return "{\"ok\":true}";
        });
        PermissionContext.force("s", PermissionMode.PLAN, false);
        RunScope scope = RunScope.capture().withSession("s");
        PermissionContext.clear();
        ToolInvocation inv = pipeline(registry).invoke(
                ToolRequest.of(scope, "write_file", "{}", 0, "r1"));
        assertEquals(ToolInvocation.Outcome.POLICY_DENIED, inv.outcome());
        assertEquals(0, calls.get());
        assertTrue(inv.text().contains("Plan"));
    }

    @Test
    void boundAndReactShareTheSamePipeline() {
        ToolRegistry registry = new ToolRegistry();
        registry.register("read_file", "r", Map.of(), x -> "{\"ok\":true}");
        ToolPipeline pipeline = pipeline(registry);
        ExecutionTurnContext.open("s", 1L, List.of(), () -> true);
        try {
            ToolResult react = pipeline.invoke(ToolRequest.of("s", "read_file", "{}", 1, "a"))
                    .result();
            ToolResult bound = pipeline.invoke(ToolRequest.bound("s", "read_file", "{}"))
                    .result();
            assertTrue(react.isSuccess());
            assertTrue(bound.isSuccess());
            assertEquals(ToolErrorCode.NONE, bound.errorCode());
        } finally {
            ExecutionTurnContext.clear();
        }
    }

    @Test
    void emptyNameIsPolicyDenied() {
        ToolInvocation inv = pipeline(new ToolRegistry()).invoke(null);
        assertEquals(ToolInvocation.Outcome.POLICY_DENIED, inv.outcome());
        assertFalse(inv.result().isSuccess());
    }

    /* =========================================================================
       exec_command 三档策略 —— 管线级
       =========================================================================

       为什么在 PermissionPolicyTest 之外还要再验一遍：那里只证了「策略函数算得对」，
       而管线里存在一个单测覆盖不到、真实会踩的错位 —— 闸门有没有真的排在
       needsSessionGrant 之前。排错了的话 isExecBlocked 依然返回 true、
       PermissionPolicyTest 依然全绿，但实际调用会被批准分支拦下去走"等批准"，
       也就是 block 档变成了 ask 档。这条只能在这里验。
       ========================================================================= */

    @Test
    void execBlockDeniesAndLeavesNoApprovalEntry() {
        AtomicInteger calls = new AtomicInteger();
        Fixture fx = new Fixture("block", execRegistry(calls));

        ToolInvocation inv = fx.pipeline.invoke(
                ToolRequest.of("s", "exec_command", "{}", 0, "r1"));

        assertEquals(ToolInvocation.Outcome.POLICY_DENIED, inv.outcome());
        assertEquals(0, calls.get(), "被禁止的工具不能真的执行");
        assertTrue(fx.trace.hasNode("PERM_DENY"), "拒绝必须留 PERM_DENY 轨迹: " + fx.trace.stepTypes());
        assertTrue(fx.trace.contentContains("\"execPolicy\":\"block\""),
                "轨迹里要能看出是哪个档位拒的: " + fx.trace.stepTypes());
        assertFalse(fx.trace.hasNode("WAITING_FOR_HUMAN"),
                "block 档不该产生批准入口 —— 那是 ask 档的行为");
        assertTrue(inv.text().contains("exec-policy=block"), "拒绝理由要能自证: " + inv.text());
    }

    @Test
    void execBlockIsNotBypassedByAcceptEditsMode() {
        // ACCEPT_EDITS 的语义是"别问我"，不是"我允许执行命令"。
        // 若有人把 block 判定挪到 mode 提升之后，这里会变成 EXECUTED —— 所以单列一条。
        AtomicInteger calls = new AtomicInteger();
        Fixture fx = new Fixture("block", execRegistry(calls));
        PermissionContext.force("s", PermissionMode.ACCEPT_EDITS, true);

        ToolInvocation inv = fx.pipeline.invoke(
                ToolRequest.of("s", "exec_command", "{}", 0, "r1"));

        assertEquals(ToolInvocation.Outcome.POLICY_DENIED, inv.outcome());
        assertEquals(0, calls.get());
    }

    @Test
    void execAskIsNotBypassedByAcceptEditsMode() {
        // 回归锁（管线级）：曾经 effectiveExecPolicy 在 ACCEPT_EDITS 下把 ask 提升成 allow，
        // 于是用户在界面上选"自动编辑"就静默得到了"任意命令免批执行"。
        // prod 档默认就是 ask，所以这条一旦回归，等于生产环境的命令审批整体失效。
        AtomicInteger calls = new AtomicInteger();
        Fixture fx = new Fixture("ask", execRegistry(calls));
        PermissionContext.force("s", PermissionMode.ACCEPT_EDITS, true);

        ToolInvocation inv = fx.pipeline.invoke(
                ToolRequest.of("s", "exec_command", "{}", 0, "r1"));

        assertEquals(ToolInvocation.Outcome.PERMISSION_ASK, inv.outcome(),
                "自动编辑模式不得把 ask 档提升成免批执行");
        assertEquals(0, calls.get(), "未获批准的命令不能执行");
        assertTrue(fx.trace.hasNode("WAITING_FOR_HUMAN"), "ask 档必须给批准入口");
    }

    @Test
    void execAskAsksOnceThenExecutesAfterGrant() {
        AtomicInteger calls = new AtomicInteger();
        Fixture fx = new Fixture("ask", execRegistry(calls));

        ToolInvocation first = fx.pipeline.invoke(
                ToolRequest.of("s", "exec_command", "{}", 0, "r1"));
        assertEquals(ToolInvocation.Outcome.PERMISSION_ASK, first.outcome());
        assertEquals(0, calls.get());
        assertTrue(fx.trace.hasNode("WAITING_FOR_HUMAN"), "ask 档必须给批准入口");
        assertFalse(fx.trace.hasNode("PERM_DENY"), "ask 是「等批准」不是「拒绝」");

        fx.store.grantAskTool("s", "exec_command");
        ToolInvocation second = fx.pipeline.invoke(
                ToolRequest.of("s", "exec_command", "{}", 0, "r2"));
        assertEquals(ToolInvocation.Outcome.EXECUTED, second.outcome());
        assertEquals(1, calls.get());
    }

    @Test
    void sessionOverrideTightensGlobalAllow() {
        // 出厂 allow + 用户在某会话里切 block：这是本功能的核心用例。
        AtomicInteger calls = new AtomicInteger();
        Fixture fx = new Fixture("allow", execRegistry(calls));
        fx.store.setExecPolicyOverride("s", ExecPolicy.BLOCK);

        assertEquals(ToolInvocation.Outcome.POLICY_DENIED,
                fx.pipeline.invoke(ToolRequest.of("s", "exec_command", "{}", 0, "r1")).outcome());
        assertEquals(0, calls.get());
    }

    @Test
    void sessionOverrideIsScopedToItsOwnSession() {
        // 覆盖只作用于本会话：别的会话仍跟随全局。少了这条，"切了策略结果全站都禁了"查不出来。
        AtomicInteger calls = new AtomicInteger();
        Fixture fx = new Fixture("allow", execRegistry(calls));
        fx.store.setExecPolicyOverride("s1", ExecPolicy.BLOCK);

        assertEquals(ToolInvocation.Outcome.POLICY_DENIED,
                fx.pipeline.invoke(ToolRequest.of("s1", "exec_command", "{}", 0, "r1")).outcome());
        assertEquals(ToolInvocation.Outcome.EXECUTED,
                fx.pipeline.invoke(ToolRequest.of("s2", "exec_command", "{}", 0, "r1")).outcome());
        assertEquals(1, calls.get());
    }

    @Test
    void clearingSessionOverrideFallsBackToGlobal() {
        AtomicInteger calls = new AtomicInteger();
        Fixture fx = new Fixture("allow", execRegistry(calls));
        fx.store.setExecPolicyOverride("s", ExecPolicy.BLOCK);
        assertEquals(ToolInvocation.Outcome.POLICY_DENIED,
                fx.pipeline.invoke(ToolRequest.of("s", "exec_command", "{}", 0, "r1")).outcome());

        // null = 清除覆盖、跟随全局 —— 撤回时必须回到全局档，而不是卡在 block。
        fx.store.setExecPolicyOverride("s", null);
        assertEquals(ToolInvocation.Outcome.EXECUTED,
                fx.pipeline.invoke(ToolRequest.of("s", "exec_command", "{}", 0, "r2")).outcome());
        assertEquals(1, calls.get());
    }

    @Test
    void execPolicyDoesNotLeakOntoNonExecTools() {
        // 策略只针对 exec_command。若 isExecBlocked 写成"block 档拒绝一切"，
        // 出厂设为 block 的环境会连 read_file 都用不了 —— 这个副作用必须在测试里挡住。
        ToolRegistry registry = new ToolRegistry();
        AtomicInteger writes = new AtomicInteger();
        registry.register("write_file", "w", Map.of(), x -> {
            writes.incrementAndGet();
            return "{\"ok\":true}";
        });
        Fixture fx = new Fixture("block", registry);

        assertEquals(ToolInvocation.Outcome.EXECUTED,
                fx.pipeline.invoke(ToolRequest.of("s", "write_file", "{}", 0, "r1")).outcome());
        assertEquals(1, writes.get());
    }

    private static final class HardGate implements LoopTurnPolicy {
        private final List<String> allowed;

        HardGate(List<String> allowed) {
            this.allowed = allowed;
        }

        @Override
        public List<String> allowedTools() {
            return allowed;
        }

        @Override
        public boolean forceToolsOnly() {
            return true;
        }

        @Override
        public boolean hardGate() {
            return true;
        }

        @Override
        public String denyTool(String toolName) {
            if (toolName != null && allowed.contains(toolName)) {
                return null;
            }
            return "{\"error\":\"hard gate deny " + toolName + "\"}";
        }

        @Override
        public String denyTodoArgs(String argumentsJson) {
            return null;
        }

        @Override
        public String focusLabel() {
            return "step";
        }

        @Override
        public Set<Integer> focusTodoIds() {
            return Set.of();
        }

        @Override
        public void markDrift() {
        }

        @Override
        public int driftHits() {
            return 0;
        }

        @Override
        public boolean consumeDrift() {
            return false;
        }
    }

    /**
     * 抓取轨迹节点，避免真的落库。
     *
     * <p>为什么断言轨迹而不只断言返回值：{@code PERM_DENY} 与 {@code WAITING_FOR_HUMAN}
     * 是两组不同结果（拒绝 / 等批准），而返回值都是"没执行"。只有轨迹能区分它们，
     * 而"block 档到底给不给批准入口"正是这次要锁的性质。
     */
    private static final class CapturingTraceRecorder extends TraceRecorder {
        private final List<String> stepTypes = new ArrayList<>();
        private final List<String> stepContents = new ArrayList<>();

        @Override
        public AgentTraceStep recordNode(String sessionId, int turn, String stepType,
                                         String content, String status, long durationMs) {
            stepTypes.add(stepType);
            stepContents.add(content);
            return null;
        }

        List<String> stepTypes() {
            return stepTypes;
        }

        boolean hasNode(String stepType) {
            return stepTypes.contains(stepType);
        }

        boolean contentContains(String needle) {
            return stepContents.stream().anyMatch(c -> c != null && c.contains(needle));
        }
    }

    /** 一条管线 + 它依赖的 store + 轨迹抓取器。store 必须与 ExecPolicyService 共用。 */
    private static final class Fixture {
        private final SessionPermissionStore store = new SessionPermissionStore();
        private final CapturingTraceRecorder trace = new CapturingTraceRecorder();
        private final ToolPipeline pipeline;

        Fixture(String globalExecPolicy, ToolRegistry registry) {
            this.pipeline = new ToolPipeline(
                    new ExecutionControl(60_000, 20, 10_000),
                    new ToolHookChain(List.of()),
                    store,
                    new InMemoryActionJournal(),
                    new ToolExecutionGuards(registry, new AgentToolsProperties(4)),
                    registry,
                    new ExecPolicyService(globalExecPolicy, "", store));
            this.pipeline.setTraceRecorder(trace);
        }
    }

    private static ToolRegistry execRegistry(AtomicInteger calls) {
        ToolRegistry registry = new ToolRegistry();
        registry.register("exec_command", "e", Map.of(), x -> {
            calls.incrementAndGet();
            return "{\"ok\":true}";
        });
        return registry;
    }

    private static ToolPipeline pipeline(ToolRegistry registry) {
        return new Fixture("allow", registry).pipeline;
    }
}
