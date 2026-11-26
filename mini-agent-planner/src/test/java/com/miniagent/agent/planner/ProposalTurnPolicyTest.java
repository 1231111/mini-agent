package com.miniagent.agent.planner;

import com.miniagent.agent.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ProposalTurnPolicyTest {

    @Test
    void dagNodeCanOnlyWriteItsDeclaredArtifactAndRunItsDeclaredAcceptanceCommand() {
        ProposalTurnPolicy policy = new ProposalTurnPolicy(
                List.of("write_file", "edit_file", "exec_command", "read_file"), true,
                "验证页面结构", Set.of(3), 0,
                Set.of("five-step-admin.test.mjs"),
                "node --test five-step-admin.test.mjs", new ProposalTurnPolicy.ProgressGuard());

        assertThat(policy.denyToolInvocation("write_file",
                "{\"path\":\"five-step-admin.test.mjs\",\"content\":\"test\"}"))
                .isNull();
        assertThat(policy.denyToolInvocation("write_file",
                "{\"path\":\"_move_obsolete.ps1\",\"content\":\"Move-Item ...\"}"))
                .contains("只能写入已声明的交付路径");
        assertThat(policy.denyToolInvocation("edit_file",
                "{\"path\":\"../outside.html\",\"oldString\":\"a\",\"newString\":\"b\"}"))
                .contains("只能写入已声明的交付路径");
        assertThat(policy.denyToolInvocation("exec_command",
                "{\"command\":\"node --test five-step-admin.test.mjs\",\"description\":\"验收\"}"))
                .isNull();
        assertThat(policy.denyToolInvocation("exec_command",
                "{\"command\":\"powershell -File _move_obsolete.ps1\",\"description\":\"整理工作区\"}"))
                .contains("只允许运行本节点声明的验收命令");
        assertThat(policy.denyToolInvocation("exec_command",
                "{\"command\":\"node --test ../outside/five-step-admin.test.mjs\"}"))
                .contains("只允许运行本节点声明的验收命令");
        assertThat(policy.denyToolInvocation("read_file", "{\"path\":\"index.html\"}"))
                .isNull();
    }

    @Test
    void softGateDoesNotAccidentallyBecomeAFileScopeOrShellGate() {
        ProposalTurnPolicy policy = new ProposalTurnPolicy(
                List.of("write_file", "edit_file", "exec_command"), false,
                "普通直跑步骤", Set.of());

        assertThat(policy.denyToolInvocation("write_file", "{\"path\":\"index.html\"}"))
                .isNull();
        assertThat(policy.denyToolInvocation("edit_file", "{\"path\":\"index.html\"}"))
                .isNull();
        assertThat(policy.denyToolInvocation("exec_command", "{\"command\":\"npm test\"}"))
                .isNull();
    }

    @Test
    void nodeToolBudgetStopsMixedCallsAndSurvivesPolicyChunkRecreation() {
        AtomicInteger used = new AtomicInteger();
        ProposalTurnPolicy.ProgressGuard shared = new ProposalTurnPolicy.ProgressGuard(() ->
                used.incrementAndGet() <= 2
                        ? ProposalTurnPolicy.ProgressGuard.BudgetDecision.allowed()
                        : ProposalTurnPolicy.ProgressGuard.BudgetDecision.denied(
                                com.miniagent.agent.core.LoopTurnPolicy.StopKind.TOOL_BUDGET_EXHAUSTED,
                                "节点预算已耗尽"));
        ProposalTurnPolicy firstChunk = new ProposalTurnPolicy(List.of("read_file"), true,
                "读取页面", Set.of(), 0, Set.of(), "", shared);

        assertThat(firstChunk.denyToolInvocation("read_file", "{\"path\":\"index.html\"}"))
                .isNull();
        assertThat(firstChunk.denyToolInvocation("read_file", "{\"path\":\"index.html\"}"))
                .isNull();
        assertThat(shared.stopReason()).isEmpty();

        ProposalTurnPolicy nextChunk = new ProposalTurnPolicy(List.of("read_file"), true,
                "读取页面", Set.of(), 0, Set.of(), "", shared);
        assertThat(nextChunk.denyToolInvocation("read_file", "{\"path\":\"style.css\"}"))
                .contains("节点预算已耗尽");
        assertThat(nextChunk.stopReason()).isEqualTo("节点预算已耗尽");
        assertThat(nextChunk.stopKind())
                .isEqualTo(com.miniagent.agent.core.LoopTurnPolicy.StopKind.TOOL_BUDGET_EXHAUSTED);
        assertThat(used).hasValue(3);
    }

    @Test
    void repeatedSuccessfulNoOpOutcomesAreCountedAcrossAgentLoopChunks() {
        ProposalTurnPolicy.ProgressGuard shared = new ProposalTurnPolicy.ProgressGuard();
        ProposalTurnPolicy firstChunk = policy(shared);
        ProposalTurnPolicy nextChunk = policy(shared);
        ToolResult sameNoOp = ToolResult.success("exit_code=0\nmissing: page.html\n");
        String quotedAbsolute = "{\"command\":\"powershell -NoProfile -File \\\"C:\\\\Users\\\\abc\\\\.miniagent\\\\workspace\\\\_move_obsolete.ps1\\\"\",\"description\":\"归档文件 A\"}";
        String relative = "{\"description\":\"归档文件 B\",\"command\":\"powershell -NoProfile -File _move_obsolete.ps1\"}";

        assertThat(firstChunk.observeToolOutcome("exec_command", quotedAbsolute, sameNoOp)).isEmpty();
        assertThat(firstChunk.observeToolOutcome("exec_command", relative, sameNoOp)).isEmpty();
        assertThat(firstChunk.observeToolOutcome("exec_command", quotedAbsolute, sameNoOp)).isEmpty();
        assertThat(nextChunk.observeToolOutcome("exec_command", relative, sameNoOp)).isEmpty();
        String stop = nextChunk.observeToolOutcome("exec_command", relative, sameNoOp);

        assertThat(stop).contains("验证页面结构", "累计 3 次", "停止该节点");
        assertThat(shared.observedCalls()).isEqualTo(5);
    }

    @Test
    void changedOutcomeHasItsOwnFingerprintButDoesNotEraseEarlierRepeatedOutcome() {
        ProposalTurnPolicy.ProgressGuard guard = new ProposalTurnPolicy.ProgressGuard();
        ProposalTurnPolicy policy = policy(guard);
        String args = "{\"command\":\"node --test structure.test.mjs\"}";

        assertThat(policy.observeToolOutcome("exec_command", args, ToolResult.success("exit_code=0\npass 1"))).isEmpty();
        assertThat(policy.observeToolOutcome("exec_command", args, ToolResult.success("exit_code=0\npass 1"))).isEmpty();
        assertThat(policy.observeToolOutcome("exec_command", args, ToolResult.success("exit_code=0\npass 2"))).isEmpty();
        assertThat(policy.observeToolOutcome("exec_command", args, ToolResult.success("exit_code=0\npass 1")))
                .contains("累计 3 次");
        assertThat(policy.denyToolInvocation("exec_command", args)).contains("累计 3 次");
    }

    private static ProposalTurnPolicy policy(ProposalTurnPolicy.ProgressGuard guard) {
        return new ProposalTurnPolicy(List.of("exec_command"), true, "验证页面结构",
                Set.of(3), 0, Set.of(), "node --test structure.test.mjs", guard);
    }
}
