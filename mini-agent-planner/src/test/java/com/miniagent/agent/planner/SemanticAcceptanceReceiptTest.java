package com.miniagent.agent.planner;

import com.miniagent.agent.todo.LlmJudgeTodoValidator;
import com.miniagent.agent.tool.ToolResult;
import com.miniagent.agent.tool.ToolStatus;
import com.miniagent.agent.tool.WorkspaceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SemanticAcceptanceReceiptTest {
    @TempDir Path workspace;
    LlmJudgeTodoValidator judge;
    StepEvaluator evaluator;

    @BeforeEach void setup() throws Exception {
        WorkspaceContext.setRootOverride(workspace);
        Files.writeString(workspace.resolve("report.md"), "Verified quarterly sales: January 10, February 20, March 30. Excludes all refund orders.");
        judge = mock(LlmJudgeTodoValidator.class);
        when(judge.acceptanceIdentity()).thenReturn("fixture-judge-instance-1");
        evaluator = new StepEvaluator(new PlannerProperties(), new PlannerMetrics(), judge);
    }

    @AfterEach void clearWorkspace() { WorkspaceContext.clearRootOverride(); }

    @Test void committedFileVerdictHalvesModelCallsButStillChecksTheFile() throws Exception {
        TaskNode node = fileNode();
        StepEvaluator.EvalResult first = evaluate(node);
        assertTrue(first.ok());
        assertNotNull(first.acceptanceProof());
        StateSnapshot committed = committed(snapshot(node), node, first);
        assertTrue(evaluator.evaluateGraph(committed.goal(), committed.graph(), committed).ok());
        verify(judge, times(2)).validate(any(), anyString()); // Two criteria once, not again at graph acceptance.

        Files.delete(workspace.resolve("report.md"));
        assertFalse(evaluator.evaluateGraph(committed.goal(), committed.graph(), committed).ok());
        verify(judge, times(2)).validate(any(), anyString());
    }

    @Test void changedFileContentInvalidatesTheReceipt() throws Exception {
        TaskNode node = fileNode();
        StateSnapshot committed = committed(snapshot(node), node, evaluate(node));
        Files.writeString(workspace.resolve("report.md"), "Changed quarterly report: refund order 999 is now included and the amounts differ.");
        assertTrue(evaluator.evaluateGraph(committed.goal(), committed.graph(), committed).ok());
        verify(judge, times(4)).validate(any(), anyString());
    }

    @Test void aReceiptHitStillChecksForFileChangesAfterReadingItsOriginalContents() {
        TaskNode node = fileNode();
        StateSnapshot committed = committed(snapshot(node), node, evaluate(node));
        when(judge.acceptanceIdentity()).thenAnswer(call -> {
            Files.writeString(workspace.resolve("report.md"), "CHANGED after reading: refund order totals no longer match the originally accepted contents.");
            return "fixture-judge-instance-1";
        });
        StepEvaluator.GraphEval accepted = evaluator.evaluateGraph(committed.goal(), committed.graph(), committed);
        assertFalse(accepted.ok());
        assertTrue(accepted.reason().contains("发生变化"));
        verify(judge, times(2)).validate(any(), anyString());
    }

    @Test void changedContractAndJudgeEachRequireFreshSemanticVerification() {
        TaskNode node = fileNode();
        StateSnapshot committed = committed(snapshot(node), node, evaluate(node));
        TaskNode changed = new TaskNode(node.id(), "Changed acceptance task", node.capability(), List.of(), List.of(),
                List.of("report"), TaskNodeStatus.SUCCESS, 0, node.doneWhen(), "", "", 0, "write receipt")
                .withOutput("write receipt", Map.of("report", "report.md")).withToolStatus(ToolStatus.SUCCESS);
        StateSnapshot changedContract = committed.withGraph(new TaskGraph(List.of(changed)));
        assertTrue(evaluator.evaluateGraph(changedContract.goal(), changedContract.graph(), changedContract).ok());
        verify(judge, times(4)).validate(any(), anyString());
        when(judge.acceptanceIdentity()).thenReturn("fixture-judge-instance-2");
        assertTrue(evaluator.evaluateGraph(committed.goal(), committed.graph(), committed).ok());
        verify(judge, times(6)).validate(any(), anyString());
    }

    @Test void executionSessionAndPlanBoundariesCannotReuseAnotherVerdict() {
        TaskNode node = fileNode();
        StateSnapshot committed = committed(snapshot(node), node, evaluate(node));
        for (StateSnapshot other : List.of(
                copyScope(committed, "other-chat", committed.executionId()),
                copyScope(committed, committed.sessionId(), "other-execution"),
                committed.revisePlan(committed.graph(), committed.goal(), "new plan"))) {
            assertTrue(evaluator.evaluateGraph(other.goal(), other.graph(), other).ok());
        }
        verify(judge, times(8)).validate(any(), anyString());
    }

    @Test void serializedProofWorksOnlyForTheExactJudgeWhileOldOrMalformedSnapshotsRevalidate() {
        TaskNode node = fileNode();
        StateSnapshot committed = committed(snapshot(node), node, evaluate(node));
        StateSnapshot restored = PlannerStateJson.snapshotFromJson(PlannerStateJson.snapshotToJson(committed));
        assertTrue(evaluator.evaluateGraph(restored.goal(), restored.graph(), restored).ok());
        verify(judge, times(2)).validate(any(), anyString());

        for (StateSnapshot legacy : List.of(committed.withExecution(Map.of()), committed.withExecution(
                Map.of(SemanticAcceptanceReceipts.EXECUTION_KEY, Map.of(node.id(), Map.of("schema", "1")))))) {
            assertTrue(evaluator.evaluateGraph(legacy.goal(), legacy.graph(), legacy).ok());
        }
        verify(judge, times(6)).validate(any(), anyString());
        when(judge.acceptanceIdentity()).thenReturn("new-process-judge");
        assertTrue(evaluator.evaluateGraph(restored.goal(), restored.graph(), restored).ok());
        verify(judge, times(8)).validate(any(), anyString());
    }

    @Test void mutableBrowserValidationIsNeverCertifiedByAFileReceipt() {
        TaskNode dynamic = new TaskNode("live", "Check current browser form", "browser", List.of(), List.of(), List.of("state"),
                TaskNodeStatus.PENDING, 0, DoneWhen.validation("").withChecks(List.of("selected field", "visible receipt")),
                "", "", 0, "");
        StepEvaluator.EvalResult first = evaluator.evaluate(dynamic, ToolResult.success("current live browser state"), "current live browser state");
        assertTrue(first.ok()); assertNull(first.acceptanceProof());
        StateSnapshot committed = committed(snapshot(dynamic), dynamic, first);
        assertTrue(evaluator.evaluateGraph(committed.goal(), committed.graph(), committed).ok());
        verify(judge, times(4)).validate(any(), anyString());
    }

    @Test void fileOrJudgeChangingWhileVerificationRunsCannotCreateASuccessfulReceipt() {
        TaskNode node = fileNode();
        doAnswer(call -> {
            Files.writeString(workspace.resolve("report.md"), "The file changed while its old content was being judged. This is another complete report.");
            return null;
        }).when(judge).validate(any(), anyString());
        StepEvaluator.EvalResult changedFile = evaluate(node);
        assertFalse(changedFile.ok()); assertNull(changedFile.acceptanceProof());
        reset(judge);
        when(judge.acceptanceIdentity()).thenReturn("before", "after");
        StepEvaluator.EvalResult changedJudge = evaluate(node);
        assertFalse(changedJudge.ok()); assertNull(changedJudge.acceptanceProof());
    }

    @Test void anUncommittedOrRejectedVerdictCannotBecomeAReceipt() {
        TaskNode node = fileNode();
        StateSnapshot original = snapshot(node);
        StepEvaluator.EvalResult candidate = evaluate(node);
        assertNotNull(candidate.acceptanceProof());
        assertFalse(original.execution().containsKey(SemanticAcceptanceReceipts.EXECUTION_KEY));
        when(judge.validate(any(), anyString())).thenReturn("fixture FAIL");
        StepEvaluator.EvalResult rejected = evaluate(node);
        assertFalse(rejected.ok()); assertNull(rejected.acceptanceProof());
        StateSnapshot next = SemanticAcceptanceReceipts.withSettledProofs(original, original.withGraph(new TaskGraph(List.of(
                node.withStatus(TaskNodeStatus.FAILED)))), Set.of(node.id()), Map.of(node.id(), candidate.acceptanceProof()));
        assertFalse(next.execution().containsKey(SemanticAcceptanceReceipts.EXECUTION_KEY));
    }

    @Test void settledWritesPruneReceiptsForRemovedNodesAndOldPlanVersions() {
        TaskNode node = fileNode();
        StateSnapshot committed = committed(snapshot(node), node, evaluate(node));
        StateSnapshot newPlan = committed.revisePlan(committed.graph(), committed.goal(), "new plan");
        StateSnapshot pruned = SemanticAcceptanceReceipts.withSettledProofs(newPlan, newPlan, Set.of(), Map.of());
        assertFalse(pruned.execution().containsKey(SemanticAcceptanceReceipts.EXECUTION_KEY));
        StateSnapshot removed = committed.withGraph(new TaskGraph(List.of()));
        assertFalse(SemanticAcceptanceReceipts.withSettledProofs(removed, removed, Set.of(), Map.of())
                .execution().containsKey(SemanticAcceptanceReceipts.EXECUTION_KEY));
    }

    private StepEvaluator.EvalResult evaluate(TaskNode node) {
        return evaluator.evaluate(node, ToolResult.success("write receipt"), "write receipt");
    }

    private static TaskNode fileNode() {
        return new TaskNode("file", "Write quarterly report", "file_write", List.of(), List.of(), List.of("report"),
                TaskNodeStatus.PENDING, 0, DoneWhen.file("report.md").withChecks(List.of("three months", "exclude refunds")),
                "", "", 0, "");
    }

    private static StateSnapshot snapshot(TaskNode node) {
        return new StateSnapshot(1, "chat", "execution", new Goal("goal", "report", "", Map.of(), List.of(), List.of()),
                new TaskGraph(List.of(node)), Map.of(), Map.of(), List.of(), 0);
    }

    private static StateSnapshot committed(StateSnapshot before, TaskNode node, StepEvaluator.EvalResult result) {
        TaskNode done = node.withStatus(TaskNodeStatus.SUCCESS).withToolStatus(ToolStatus.SUCCESS)
                .withOutput("write receipt", Map.of(node.outputs().getFirst(), "bound evidence"));
        return SemanticAcceptanceReceipts.withSettledProofs(before, before.withGraph(new TaskGraph(List.of(done))),
                Set.of(node.id()), result.acceptanceProof() == null ? Map.of() : Map.of(node.id(), result.acceptanceProof()));
    }

    private static StateSnapshot copyScope(StateSnapshot snapshot, String session, String execution) {
        return new StateSnapshot(snapshot.version(), session, execution, snapshot.goal(), snapshot.graph(), snapshot.execution(),
                snapshot.environment(), snapshot.knowledgeRefs(), snapshot.recoveryCount(), snapshot.planRevision());
    }
}
