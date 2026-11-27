package com.miniagent.agent.planner;

import com.miniagent.agent.core.LoopTurnPolicy;
import com.miniagent.agent.tool.ToolErrorCode;
import com.miniagent.agent.tool.ToolResult;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RecoveryReadbackPolicyTest {
    private static final String PATH = "D:/workspace/index.html";
    private static final String READ = "{\"path\":\"" + PATH + "\"}";
    private static final String WRITE = "{\"path\":\"" + PATH + "\",\"content\":\"updated\"}";

    @Test
    void readsRealResultThenAdmitsOneWriteAndRequiresFreshReadForTheNext() {
        Fixture fixture = fixture(LoopTurnPolicy.NONE);
        assertThat(fixture.policy.denyToolInvocation("write_file", WRITE)).contains("只读");

        fixture.policy.observeToolOutcome("read_file", READ, ToolResult.success("current page"));
        assertThat(fixture.policy.liveVerification().evidence().observations()).hasSize(1);
        assertThat(fixture.policy.liveVerification().evidence().completeness().complete()).isTrue();
        assertThat(fixture.policy.denyToolInvocation("write_file", WRITE)).isNull();
        assertThat(fixture.policy.denyRewrittenToolInvocation("write_file", WRITE)).isNull();
        fixture.policy.observeToolOutcome("write_file", WRITE, ToolResult.success("written"));

        assertThat(fixture.policy.denyToolInvocation("write_file", WRITE)).isNotNull();
        read(fixture.policy);
        assertThat(fixture.policy.denyToolInvocation("write_file", WRITE)).isNull();
    }

    @Test
    void rewrittenArgumentsMustMatchTheReadTargetAndDoNotSpendReadbackWhenDenied() {
        Fixture fixture = fixture(LoopTurnPolicy.NONE);
        read(fixture.policy);
        assertThat(fixture.policy.denyToolInvocation("write_file", WRITE)).isNull();
        String changed = "{\"path\":\"D:/workspace/other.html\",\"content\":\"updated\"}";

        assertThat(fixture.policy.denyRewrittenToolInvocation("write_file", changed)).isNotNull();
        assertThat(fixture.policy.denyRewrittenToolInvocation("write_file", WRITE)).isNull();
    }

    @Test
    void failedOrTimedOutMutationCannotReuseItsReadback() {
        Fixture fixture = fixture(LoopTurnPolicy.NONE);
        read(fixture.policy);
        assertThat(fixture.policy.denyToolInvocation("write_file", WRITE)).isNull();
        assertThat(fixture.policy.denyRewrittenToolInvocation("write_file", WRITE)).isNull();
        fixture.policy.observeToolOutcome("write_file", WRITE,
                ToolResult.failure(ToolErrorCode.TIMEOUT, "unknown outcome", false));

        assertThat(fixture.policy.hasFreshReadback()).isFalse();
        assertThat(fixture.policy.denyToolInvocation("write_file", WRITE)).isNotNull();
    }

    @Test
    void modelVisibleSuccessCannotOverrideActualFailedRead() {
        Fixture fixture = fixture(LoopTurnPolicy.NONE);
        fixture.policy.observeToolOutcome("read_file", READ,
                ToolResult.failure(ToolErrorCode.NOT_FOUND, "missing", false), "success");

        assertThat(fixture.policy.denyToolInvocation("write_file", WRITE)).isNotNull();
        assertThat(fixture.policy.liveVerification().evidence().observations().get(0).success()).isFalse();
    }

    @Test
    void todoSubmissionDelegatesNormallyAndCannotManufactureEvidence() {
        CountingPolicy delegate = new CountingPolicy();
        Fixture fixture = fixture(delegate);
        String submit = "{\"action\":\"submit\",\"id\":1,\"evidence\":\"I read " + PATH + "\"}";

        assertThat(fixture.policy.denyToolInvocation("todo", submit)).isNull();
        assertThat(delegate.invocations).isEqualTo(1);
        fixture.policy.observeToolOutcome("todo", submit, ToolResult.success("submitted"));
        assertThat(fixture.policy.liveVerification().evidence().observations()).isEmpty();
        assertThat(fixture.policy.denyToolInvocation("write_file", WRITE)).isNotNull();
        assertThat(delegate.invocations).isEqualTo(1);
    }

    @Test
    void parentCheckedPredecessorCanAuthorizeOnlyItsOwnScopeAndIsConsumedOnce() {
        TaskNode node = node();
        StateSnapshot snapshot = recovery(node);
        ExecutionEvidence evidence = readEvidence();
        var predecessor = new RecoveryExecutionGuard.Verification(snapshot.executionId(),
                snapshot.planVersion(), "readback-predecessor", evidence);
        RecoveryReadbackPolicy policy = new RecoveryReadbackPolicy(snapshot, node, LoopTurnPolicy.NONE, predecessor);

        assertThat(policy.denyToolInvocation("write_file", WRITE)).isNull();
        assertThat(policy.denyRewrittenToolInvocation("write_file", WRITE)).isNull();
        assertThat(policy.denyToolInvocation("write_file", WRITE)).isNotNull();

        var stale = new RecoveryExecutionGuard.Verification("old-execution", snapshot.planVersion(),
                "readback-predecessor", evidence);
        assertThat(new RecoveryReadbackPolicy(snapshot, node, LoopTurnPolicy.NONE, stale)
                .denyToolInvocation("write_file", WRITE)).isNotNull();
    }

    @Test
    void delegateDenialStillWinsAfterTheReadbackCheck() {
        CountingPolicy delegate = new CountingPolicy();
        delegate.deny = "delegate denied";
        Fixture fixture = fixture(delegate);
        fixture.policy.observeToolOutcome("read_file", READ, ToolResult.success("current page"));

        assertThat(fixture.policy.denyToolInvocation("write_file", WRITE)).isEqualTo("delegate denied");
        assertThat(fixture.policy.hasFreshReadback()).isTrue();
        assertThat(fixture.policy.allowedTools()).containsExactly("read_file", "write_file", "todo");
        assertThat(fixture.policy.focusTodoIds()).containsExactly(1);
    }

    @Test
    void longReadReceiptAndMalformedWriteParametersFailClosed() {
        Fixture fixture = fixture(LoopTurnPolicy.NONE);
        fixture.policy.observeToolOutcome("read_file", READ, ToolResult.success("x".repeat(5000)));
        assertThat(fixture.policy.denyToolInvocation("write_file", WRITE)).isNotNull();
        assertThat(fixture.policy.denyToolInvocation("write_file", "not-json")).contains("无法可靠解析");
    }

    private static void read(RecoveryReadbackPolicy policy) {
        assertThat(policy.denyToolInvocation("read_file", READ)).isNull();
        assertThat(policy.denyRewrittenToolInvocation("read_file", READ)).isNull();
        policy.observeToolOutcome("read_file", READ, ToolResult.success("<html>current</html>"));
    }

    private static Fixture fixture(LoopTurnPolicy delegate) {
        TaskNode node = node();
        return new Fixture(new RecoveryReadbackPolicy(recovery(node), node, delegate));
    }

    private static ExecutionEvidence readEvidence() {
        List<ChatMessage> messages = List.of(
                AiMessage.from(ToolExecutionRequest.builder().id("read1").name("read_file").arguments(READ).build()),
                ToolExecutionResultMessage.from("read1", "read_file", ToolResult.success("current page").legacyText()));
        return ExecutionEvidence.fromEnvelope(ExecutionEvidence.build("", messages));
    }

    private static TaskNode node() {
        return new TaskNode("repair", "repair page", "file_write", List.of(), List.of(), List.of("page"),
                TaskNodeStatus.PENDING, 100, DoneWhen.file(PATH), "", "", 0, "");
    }

    private static StateSnapshot recovery(TaskNode node) {
        TaskNode original = node.withOutput("legacy successful write receipt")
                .withToolStatus(com.miniagent.agent.tool.ToolStatus.SUCCESS);
        StateSnapshot source = new StateSnapshot(1, "session", "execution", new Goal("goal", "repair", "", "code",
                Map.of(), List.of(), List.of()), new TaskGraph(List.of(original)), Map.of(), Map.of(), List.of(), 0);
        StateSnapshot target = source.revisePlan(source.graph(), source.goal(), "recovery");
        return RecoveryExecutionGuard.register(target, source, target.graph());
    }

    private record Fixture(RecoveryReadbackPolicy policy) { }

    private static final class CountingPolicy implements LoopTurnPolicy {
        private int invocations;
        private String deny;
        @Override public List<String> allowedTools() { return List.of("read_file", "write_file", "todo"); }
        @Override public boolean forceToolsOnly() { return true; }
        @Override public boolean hardGate() { return true; }
        @Override public String denyTool(String toolName) { return null; }
        @Override public String denyTodoArgs(String argumentsJson) { return null; }
        @Override public String denyToolInvocation(String toolName, String argumentsJson) { invocations++; return deny; }
        @Override public String focusLabel() { return "repair page"; }
        @Override public Set<Integer> focusTodoIds() { return Set.of(1); }
        @Override public void markDrift() { }
        @Override public int driftHits() { return 0; }
        @Override public boolean consumeDrift() { return false; }
    }
}
