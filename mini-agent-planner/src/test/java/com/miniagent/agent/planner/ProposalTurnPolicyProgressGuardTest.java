package com.miniagent.agent.planner;

import com.miniagent.agent.core.LoopTurnPolicy;
import com.miniagent.agent.tool.ToolErrorCode;
import com.miniagent.agent.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ProposalTurnPolicyProgressGuardTest {

    @Test
    void interleavedDeterministicFailuresStopAfterThirdOccurrenceAndBlockFourthDispatch() {
        ProposalTurnPolicy.ProgressGuard guard = new ProposalTurnPolicy.ProgressGuard();
        ProposalTurnPolicy policy = new ProposalTurnPolicy(List.of("list_files"), true,
                "盘点工作区文件", Set.of(), 0, Set.of(), "", guard);
        String missingArgs = "{\"path\":\"workspace/shared\"}";
        ToolResult missing = ToolResult.failure(ToolErrorCode.NOT_FOUND,
                "文件不存在: workspace/shared", false);

        assertThat(policy.denyToolInvocation("list_files", missingArgs)).isNull();
        assertThat(policy.observeToolOutcome("list_files", missingArgs, missing)).isEmpty();
        observeDistinctSuccess(policy, "workspace", "root listing round 1");
        observeDistinctSuccess(policy, "workspace/test", "test listing round 1");

        assertThat(policy.denyToolInvocation("list_files", missingArgs)).isNull();
        assertThat(policy.observeToolOutcome("list_files", missingArgs, missing)).isEmpty();
        observeDistinctSuccess(policy, "workspace", "root listing round 2");
        observeDistinctSuccess(policy, "workspace/test", "test listing round 2");

        assertThat(policy.denyToolInvocation("list_files", missingArgs)).isNull();
        String stop = policy.observeToolOutcome("list_files", missingArgs, missing);

        assertThat(stop).contains("累计 3 次", "未产生可验证的新进展");
        assertThat(policy.stopKind()).isEqualTo(LoopTurnPolicy.StopKind.NO_PROGRESS);
        assertThat(policy.denyToolInvocation("list_files", missingArgs)).contains("累计 3 次");
        assertThat(guard.observedCalls()).isEqualTo(7);
    }

    @Test
    void trackedOutcomeFingerprintsRemainBoundedForLongRunningNodes() {
        ProposalTurnPolicy.ProgressGuard guard = new ProposalTurnPolicy.ProgressGuard();

        for (int i = 0; i < 1_000; i++) {
            String path = "{\"path\":\"workspace/file-" + i + ".txt\"}";
            String result = "listing " + i;
            assertThat(guard.observe("list_files", path, ToolResult.success(result), "scan")).isEmpty();
        }

        assertThat(guard.trackedOutcomeFingerprints()).isEqualTo(256);
        assertThat(guard.observedCalls()).isEqualTo(1_000);
        assertThat(guard.stopReason()).isEmpty();
    }

    @Test
    void repeatedToolCallWithDifferentVisibleChunksDoesNotTripUntilSameChunkRepeats() {
        ProposalTurnPolicy.ProgressGuard guard = new ProposalTurnPolicy.ProgressGuard();
        ProposalTurnPolicy policy = new ProposalTurnPolicy(List.of("read_file"), true,
                "读取长文件", Set.of(), 0, Set.of(), "", guard);
        String args = "{\"path\":\"large.html\"}";
        ToolResult fullCachedResult = ToolResult.success("same cached full file response");

        assertThat(policy.observeToolOutcome("read_file", args, fullCachedResult, "chunk 1 of 3"))
                .isEmpty();
        assertThat(policy.observeToolOutcome("read_file", args, fullCachedResult, "chunk 2 of 3"))
                .isEmpty();
        assertThat(policy.observeToolOutcome("read_file", args, fullCachedResult, "chunk 3 of 3"))
                .isEmpty();
        assertThat(policy.observeToolOutcome("read_file", args, fullCachedResult, "chunk 1 of 3"))
                .isEmpty();
        assertThat(policy.observeToolOutcome("read_file", args, fullCachedResult, "chunk 1 of 3"))
                .contains("累计 3 次");
    }

    private static void observeDistinctSuccess(ProposalTurnPolicy policy, String path, String result) {
        String args = "{\"path\":\"" + path + "\"}";
        assertThat(policy.denyToolInvocation("list_files", args)).isNull();
        assertThat(policy.observeToolOutcome("list_files", args, ToolResult.success(result))).isEmpty();
    }
}
