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

    private static TaskNode node(String id, List<String> dependsOn, TaskNodeStatus status) {
        return new TaskNode(id, id, "research", dependsOn, List.of(), List.of(), status,
                10, DoneWhen.note(), "", "", 0, "");
    }
}
