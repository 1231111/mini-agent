package com.miniagent.agent.planner;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PlanningLoopReplanMergeTest {

    @Test
    void preservesDependenciesAndCompleteNodeMetadataInUnsortedGraph() {
        TaskNode completed = node("completed", List.of(), TaskNodeStatus.SUCCESS);
        TaskNode submit = new TaskNode("submit", "提交", "browser", List.of("prepare"),
                List.of("url"), List.of(), TaskNodeStatus.PENDING, 8,
                DoneWhen.note(), "browser_open", Map.of("url", "https://example.com"),
                "undo submit", List.of("criterion-1"), "", 0,
                "", Map.of("result", "bound output"))
                .withProcedure(12L, 3)
                .withEvolutionAssignment(9L, "a".repeat(64), "CANDIDATE", 25)
                .withProcedureSnapshot("{\"step\":\"submit\"}");
        TaskNode prepare = node("prepare", List.of(), TaskNodeStatus.PENDING);

        TaskGraph merged = PlanningLoop.mergeKeepSuccess(new TaskGraph(List.of(completed)),
                new TaskGraph(List.of(submit, prepare)), 1);

        assertNotNull(merged);
        TaskNode mergedSubmit = merged.byId("rp1_submit");
        assertEquals(List.of("rp1_prepare"), mergedSubmit.dependsOn());
        assertEquals(Map.of("url", "https://example.com"), mergedSubmit.toolArguments());
        assertEquals("undo submit", mergedSubmit.compensation());
        assertEquals(List.of("criterion-1"), mergedSubmit.covers());
        assertEquals(Map.of("result", "bound output"), mergedSubmit.outputBindings());
        assertEquals(12L, mergedSubmit.procedureId());
        assertEquals(3, mergedSubmit.procedureVersion());
        assertEquals(9L, mergedSubmit.evolutionProposalId());
        assertEquals("a".repeat(64), mergedSubmit.evolutionCandidateHash());
        assertEquals("CANDIDATE", mergedSubmit.procedureVariant());
        assertEquals(25, mergedSubmit.canaryTargetPercent());
        assertEquals("{\"step\":\"submit\"}", mergedSubmit.procedureSnapshotJson());
        assertEquals(List.of("completed"), merged.byId("rp1_prepare").dependsOn());
        assertEquals(List.of("rp1_prepare"), merged.normalizeForScheduling().readyNodes()
                .stream().map(TaskNode::id).toList());
    }

    @Test
    void preservesReferenceToOldSuccessfulNodeAndRejectsUnknownDependency() {
        TaskGraph oldGraph = new TaskGraph(List.of(node("completed", List.of(), TaskNodeStatus.SUCCESS)));
        TaskGraph merged = PlanningLoop.mergeKeepSuccess(oldGraph,
                new TaskGraph(List.of(node("next", List.of("completed"), TaskNodeStatus.PENDING))), 2);
        assertNotNull(merged);
        assertEquals(List.of("completed"), merged.byId("rp2_next").dependsOn());
        assertNull(PlanningLoop.mergeKeepSuccess(oldGraph,
                new TaskGraph(List.of(node("next", List.of("missing"), TaskNodeStatus.PENDING))), 2));
    }

    @Test
    void rejectsGeneratedIdCollisionWithSuccessfulReceipt() {
        TaskGraph completed = new TaskGraph(List.of(node("rp1_submit", List.of(), TaskNodeStatus.SUCCESS)));
        TaskGraph replacement = new TaskGraph(List.of(node("submit", List.of(), TaskNodeStatus.PENDING)));
        assertNull(PlanningLoop.mergeKeepSuccess(completed, replacement, 1));
    }

    @Test
    void reusesRenamedBoundActionAndRebindsItsConsumers() {
        TaskNode completed = boundAction("written", TaskNodeStatus.SUCCESS, Map.of("path", "report.md"));
        TaskNode renamed = completed.withIdentityAndDependencies("renamed", List.of())
                .withStatus(TaskNodeStatus.PENDING);
        TaskGraph merged = PlanningLoop.mergeKeepSuccess(new TaskGraph(List.of(completed)),
                new TaskGraph(List.of(renamed, node("finish", List.of("renamed"), TaskNodeStatus.PENDING))), 1);
        assertNotNull(merged);
        assertEquals(2, merged.nodes().size());
        assertSame(completed, merged.byId("written"));
        assertNull(merged.byId("rp1_renamed"));
        assertEquals(List.of("written"), merged.byId("rp1_finish").dependsOn());
    }

    @Test
    void rejectsRenamedActionWithConflictingOutputBindings() {
        TaskNode completed = boundAction("written", TaskNodeStatus.SUCCESS, Map.of("path", "report.md"));
        TaskNode renamed = boundAction("renamed", TaskNodeStatus.PENDING, Map.of("path", "different.md"));
        assertNull(PlanningLoop.mergeKeepSuccess(new TaskGraph(List.of(completed)),
                new TaskGraph(List.of(renamed)), 1));
    }

    @Test
    void reusesCompletedReceiptWhenNewPlanDoesNotDeclareRuntimeOutputBindings() {
        TaskNode completed = boundAction("written", TaskNodeStatus.SUCCESS, Map.of("path", "report.md"));
        TaskNode renamed = boundAction("renamed", TaskNodeStatus.PENDING, Map.of());
        TaskGraph merged = PlanningLoop.mergeKeepSuccess(new TaskGraph(List.of(completed)),
                new TaskGraph(List.of(renamed)), 1);
        assertNotNull(merged);
        assertEquals(List.of(completed), merged.nodes());
        assertEquals(Map.of("path", "report.md"), merged.byId("written").outputBindings());
    }

    @Test
    void doesNotDeduplicateUnboundPlanningStepsOnlyBecauseTheirMetadataMatches() {
        TaskNode completed = node("completed", List.of(), TaskNodeStatus.SUCCESS);
        TaskNode renamed = node("renamed", List.of(), TaskNodeStatus.PENDING);
        TaskGraph merged = PlanningLoop.mergeKeepSuccess(new TaskGraph(List.of(completed)),
                new TaskGraph(List.of(renamed)), 1);
        assertNotNull(merged);
        assertEquals(2, merged.nodes().size());
        assertEquals(List.of("completed"), merged.byId("rp1_renamed").dependsOn());
    }

    @Test
    void rejectsDifferentUnboundActionsThatShareOutputName() {
        TaskNode completed = unboundQuery("query-a", "查询 A", TaskNodeStatus.SUCCESS);
        TaskNode replacement = unboundQuery("query-b", "查询 B", TaskNodeStatus.PENDING);
        assertNull(PlanningLoop.mergeKeepSuccess(new TaskGraph(List.of(completed)),
                new TaskGraph(List.of(replacement)), 1));
    }

    @Test
    void rejectsSameIdWhenBusinessMeaningChanges() {
        TaskNode completed = unboundQuery("query", "查询 A", TaskNodeStatus.SUCCESS);
        TaskNode replacement = unboundQuery("query", "查询 B", TaskNodeStatus.PENDING);
        assertNull(PlanningLoop.mergeKeepSuccess(new TaskGraph(List.of(completed)),
                new TaskGraph(List.of(replacement)), 1));
    }

    private static TaskNode unboundQuery(String id, String name, TaskNodeStatus status) {
        return new TaskNode(id, name, "web", List.of(), List.of("query"),
                List.of("query-result"), status, 10, DoneWhen.note(), "", "", 0, "");
    }

    private static TaskNode boundAction(String id, TaskNodeStatus status, Map<String, String> bindings) {
        return new TaskNode(id, id, "file_write", List.of(), List.of(), List.of(), status,
                10, DoneWhen.note(), "file_write", Map.of("path", "report.md", "content", "done"),
                "", List.of(), "", 0, "", bindings);
    }

    private static TaskNode node(String id, List<String> dependsOn, TaskNodeStatus status) {
        return new TaskNode(id, id, "research", dependsOn, List.of(), List.of(), status,
                10, DoneWhen.note(), "", "", 0, "");
    }
}
