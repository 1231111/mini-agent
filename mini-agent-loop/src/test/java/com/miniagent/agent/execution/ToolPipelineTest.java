package com.miniagent.agent.execution;

import com.miniagent.agent.core.ExecutionControl;
import com.miniagent.agent.core.ExecutionTurnContext;
import com.miniagent.agent.core.LoopTurnContext;
import com.miniagent.agent.core.LoopTurnPolicy;
import com.miniagent.agent.core.RunScope;
import com.miniagent.agent.hook.ToolHookChain;
import com.miniagent.agent.permission.ExecPolicyService;
import com.miniagent.agent.permission.AskGrant;
import com.miniagent.agent.permission.ConfirmPolicy;
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
        // 本类有两条用例临时绑定存储：清掉，避免静态状态串到同模块其他用例。
        PermissionContext.bindStore(null);
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

    /**
     * 真实链路：用户点两个开关 → 值落进会话存储 → 管道按存储现场取值 → 不产生授权入口。
     *
     * <p>为什么必须补这一条：{@link #fullAutoExecutesWithoutAnyPermissionAsk} 用
     * {@code PermissionContext.force} 直接塞了一份权限快照，**绕过了存储这一段**。
     * 只要存储的读、写或枚举解析有一处坏掉，force 版本照样全绿，而真实用户点完开关
     * 仍然会被弹窗拦住 —— 那正是"明明开了自动还在问"的成因。这条用例走的是主链路：
     * {@code setMode}/{@code setConfirmPolicy} 写入，{@code setSession} 绑定，
     * {@code RunScope.capture()} 每次现场读，不借助任何强制值。</p>
     *
     * <p>同时把全局 exec 策略设成 {@code ask}（线上默认值，见 application.yml），
     * 也就是说：这不是靠把 exec 提到 allow 才免问的，而是全自动本身绕过授权入口。</p>
     */
    @Test
    void bothSwitchesThroughSessionStoreSkipApprovalOnRealPath() {
        AtomicInteger calls = new AtomicInteger();
        Fixture fx = new Fixture("ask", execRegistry(calls));
        // 页面上两次 PUT：先点「自动编辑」，再点「自动确认」
        fx.store.setMode("s", PermissionMode.ACCEPT_EDITS);
        fx.store.setConfirmPolicy("s", ConfirmPolicy.AUTO);
        PermissionContext.bindStore(fx.store);
        // 主链路用 setSession，不是 force：每一轮 capture() 现场读存储
        PermissionContext.setSession("s");

        ToolInvocation inv = fx.pipeline.invoke(
                ToolRequest.of("s", "exec_command", "{\"command\":\"echo hi\"}", 0, "r1")
                        .withApprovalWait());

        assertEquals(ToolInvocation.Outcome.EXECUTED, inv.outcome(),
                "存储里两个开关都开着，管道必须直接执行");
        assertEquals(1, calls.get(), "工具真的进入了执行阶段");
        assertFalse(fx.trace.hasNode("WAITING_FOR_HUMAN"), "不得产生任何批准入口");
        assertTrue(fx.trace.hasNode("PERM_AUTO"), "要留全自动放行轨迹，便于和本来免授权的调用区分");
    }

    /**
     * 反例：存储里**只**有「自动编辑」，全局 exec 策略仍是 ask → 仍然要问。
     *
     * <p>与上一条成对存在。只证明"开了就放行"是不够的 —— 那可能只是把授权整个关掉了；
     * 这条证明开关组合是必要条件，单开自动编辑不会顺带放开终端命令。</p>
     */
    @Test
    void acceptEditsAloneThroughStoreStillAsks() {
        AtomicInteger calls = new AtomicInteger();
        Fixture fx = new Fixture("ask", execRegistry(calls));
        fx.store.setMode("s", PermissionMode.ACCEPT_EDITS);
        fx.store.setConfirmPolicy("s", ConfirmPolicy.DANGEROUS);
        PermissionContext.bindStore(fx.store);
        PermissionContext.setSession("s");

        // 注意：这里**不**加 withApprovalWait()。预期就是「要问」，
        // 带上它就是真的挂到审批 TTL 结束，一条反例会把测试套件拖住五分钟。
        ToolInvocation inv = fx.pipeline.invoke(
                ToolRequest.of("s", "exec_command", "{\"command\":\"echo hi\"}", 0, "r1"));

        assertEquals(ToolInvocation.Outcome.PERMISSION_ASK, inv.outcome(),
                "只开自动编辑时，终端命令仍须用户批准");
        assertEquals(0, calls.get(), "未批准前不得执行");
        assertFalse(fx.trace.hasNode("PERM_AUTO"), "不是全自动，不应记全自动放行");
    }

    @Test
    void fullAutoExecutesWithoutAnyPermissionAsk() {
        AtomicInteger calls = new AtomicInteger();
        Fixture fx = new Fixture("ask", execRegistry(calls));
        // 用户明确打开「自动编辑 + 自动确认」
        PermissionContext.force("s", PermissionMode.ACCEPT_EDITS, ConfirmPolicy.AUTO, true);

        ToolInvocation inv = fx.pipeline.invoke(
                ToolRequest.of("s", "exec_command", "{\"command\":\"echo hi\"}", 0, "r1")
                        .withApprovalWait());

        assertEquals(ToolInvocation.Outcome.EXECUTED, inv.outcome());
        assertEquals(1, calls.get());
        assertFalse(fx.trace.hasNode("WAITING_FOR_HUMAN"), "全自动不应产生批准入口");
        assertTrue(fx.trace.hasNode("PERM_AUTO"),
                "必须留下「被全自动放行」的轨迹，否则事后无法与本来就免授权的调用区分");
    }

    @Test
    void switchingToFullAutoMidTaskStopsFurtherAsks() {
        AtomicInteger calls = new AtomicInteger();
        Fixture fx = new Fixture("ask", execRegistry(calls));

        // 任务进行中：默认模式 + 危险确认 → 第一步仍要批准
        ToolInvocation first = fx.pipeline.invoke(
                ToolRequest.of("s", "exec_command", "{\"command\":\"echo 1\"}", 0, "r1"));
        assertEquals(ToolInvocation.Outcome.PERMISSION_ASK, first.outcome());
        assertEquals(0, calls.get());

        // 用户不改任务、只把两个开关打开
        PermissionContext.force("s", PermissionMode.ACCEPT_EDITS, ConfirmPolicy.AUTO, true);

        ToolInvocation second = fx.pipeline.invoke(
                ToolRequest.of("s", "exec_command", "{\"command\":\"echo 2\"}", 0, "r2")
                        .withApprovalWait());
        assertEquals(ToolInvocation.Outcome.EXECUTED, second.outcome(),
                "切换之后后续步骤不得再询问");
        assertEquals(1, calls.get());
    }

    @Test
    void fullAutoDoesNotBypassExecBlock() {
        AtomicInteger calls = new AtomicInteger();
        Fixture fx = new Fixture("block", execRegistry(calls));
        PermissionContext.force("s", PermissionMode.ACCEPT_EDITS, ConfirmPolicy.AUTO, true);

        ToolInvocation inv = fx.pipeline.invoke(
                ToolRequest.of("s", "exec_command", "{}", 0, "r1").withApprovalWait());

        assertEquals(ToolInvocation.Outcome.POLICY_DENIED, inv.outcome(),
                "全自动是「不询问」，不是「允许执行」；禁止档仍是硬拒绝");
        assertEquals(0, calls.get());
        assertTrue(fx.trace.hasNode("PERM_DENY"));
    }

    @Test
    void autoConfirmAloneKeepsAskingForExec() {
        AtomicInteger calls = new AtomicInteger();
        Fixture fx = new Fixture("ask", execRegistry(calls));
        // 只开「自动确认」，没开「自动编辑」
        PermissionContext.force("s", PermissionMode.DEFAULT, ConfirmPolicy.AUTO, true);

        ToolInvocation inv = fx.pipeline.invoke(
                ToolRequest.of("s", "exec_command", "{}", 0, "r1"));

        assertEquals(ToolInvocation.Outcome.PERMISSION_ASK, inv.outcome());
        assertEquals(0, calls.get());
    }

    @Test
    void approvalResumesTheOriginalInvocationExactlyOnce() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Fixture fx = new Fixture("ask", execRegistry(calls));
        String args = "{\"command\":\"echo one\"}";
        AskGrant grant = fx.store.issueAskGrant("s", "exec_command", args, 30_000L);
        AtomicReference<ToolInvocation> result = new AtomicReference<>();
        Thread worker = new Thread(() -> result.set(fx.pipeline.invoke(
                ToolRequest.of("s", "exec_command", args, 0, "r1")
                        .withApprovalWait())));
        worker.start();
        long deadline = System.currentTimeMillis() + 2_000L;
        while (!fx.store.hasAttachedAskResume("s", grant.token())
                && System.currentTimeMillis() < deadline) {
            Thread.yield();
        }
        assertTrue(fx.store.hasAttachedAskResume("s", grant.token()));
        assertTrue(fx.store.grantAskTokenForResume("s", grant.token(), "exec_command").isPresent());
        worker.join(2_000L);

        assertFalse(worker.isAlive(), "原调用应在批准后完成");
        assertEquals(ToolInvocation.Outcome.EXECUTED, result.get().outcome());
        assertEquals(1, calls.get());
        assertFalse(fx.store.isAskGranted("s", "exec_command", "{\"command\":\"echo one\"}"),
                "单次续跑批准不能持久化为可重放 grant");
    }

    @Test
    void denyingApprovalWakesOriginalInvocationWithoutExecuting() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Fixture fx = new Fixture("ask", execRegistry(calls));
        AskGrant grant = fx.store.issueAskGrant("s", "exec_command", "{}", 30_000L);
        AtomicReference<ToolInvocation> result = new AtomicReference<>();
        Thread worker = new Thread(() -> result.set(fx.pipeline.invoke(
                ToolRequest.of("s", "exec_command", "{}", 0, "r1").withApprovalWait())));
        worker.start();
        long deadline = System.currentTimeMillis() + 2_000L;
        while (!fx.store.hasAttachedAskResume("s", grant.token())
                && System.currentTimeMillis() < deadline) {
            Thread.yield();
        }
        assertTrue(fx.store.hasAttachedAskResume("s", grant.token()));
        assertTrue(fx.store.denyAskToken("s", grant.token(), "exec_command"));
        worker.join(2_000L);

        assertFalse(worker.isAlive(), "拒绝应結束原调用的等待");
        assertEquals(ToolInvocation.Outcome.PERMISSION_DENIED, result.get().outcome());
        assertEquals(0, calls.get());
    }

    @Test
    void legacyToolGrantDoesNotAuthorizeDifferentArguments() {
        AtomicInteger calls = new AtomicInteger();
        Fixture fx = new Fixture("ask", execRegistry(calls));
        fx.store.grantAskTool("s", "exec_command");

        ToolInvocation invocation = fx.pipeline.invoke(ToolRequest.of(
                "s", "exec_command", "{\"command\":\"different\"}", 0, "r2"));

        assertEquals(ToolInvocation.Outcome.PERMISSION_ASK, invocation.outcome());
        assertEquals(0, calls.get(), "a compatibility grant must remain argument scoped");
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
