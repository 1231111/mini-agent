package com.miniagent.agent.planner;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.miniagent.agent.tool.ToolStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskGraphUnknownOutcomeTest {

    @Test
    void interruptedDispatchCannotBecomeReadyOrUnlockItsDependent() {
        TaskNode dispatched = node("n1", List.of(), TaskNodeStatus.RUNNING)
                .withToolStatus(ToolStatus.UNKNOWN);
        TaskNode dependent = node("n2", List.of("n1"), TaskNodeStatus.READY);

        TaskGraph normalized = new TaskGraph(List.of(dispatched, dependent)).normalizeForScheduling();

        assertEquals(TaskNodeStatus.OUTCOME_UNKNOWN, normalized.byId("n1").status());
        assertEquals(ToolStatus.UNKNOWN, normalized.byId("n1").toolStatus());
        assertTrue(normalized.byId("n1").lastError().startsWith("OUTCOME_UNKNOWN:"));
        assertEquals(TaskNodeStatus.PENDING, normalized.byId("n2").status());
        assertTrue(normalized.readyNodes().isEmpty());
        assertFalse(normalized.allTerminalSuccess());
    }

    @Test
    void readyNodeWithSuccessfulDependenciesRemainsReady() {
        TaskNode completed = node("n1", List.of(), TaskNodeStatus.SUCCESS);
        TaskNode ready = node("n2", List.of("n1"), TaskNodeStatus.READY);

        TaskGraph normalized = new TaskGraph(List.of(completed, ready)).normalizeForScheduling();

        assertEquals(TaskNodeStatus.READY, normalized.byId("n2").status());
        assertEquals(List.of("n2"), normalized.readyNodes().stream().map(TaskNode::id).toList());
    }

    @Test
    void quarantinedNodeRemainsBlockedWithoutAnExecutionReceipt() {
        TaskGraph normalized = new TaskGraph(List.of(
                node("n1", List.of(), TaskNodeStatus.OUTCOME_UNKNOWN)))
                .normalizeForScheduling();

        assertEquals(TaskNodeStatus.OUTCOME_UNKNOWN, normalized.byId("n1").status());
        assertTrue(normalized.readyNodes().isEmpty());
        assertFalse(normalized.allTerminalSuccess());
    }

    @Test
    void legacyInterruptedRunningNodeWithoutReceiptIsAlsoQuarantined() {
        TaskGraph normalized = new TaskGraph(List.of(
                node("n1", List.of(), TaskNodeStatus.RUNNING)))
                .normalizeForScheduling();

        assertEquals(TaskNodeStatus.OUTCOME_UNKNOWN, normalized.byId("n1").status());
        assertTrue(normalized.byId("n1").lastError().startsWith("OUTCOME_UNKNOWN:"));
        assertTrue(normalized.readyNodes().isEmpty());
    }

    @Test
    void snapshotRoundTripPreservesQuarantineAndExecutionReceipt() {
        TaskNode unknown = node("n1", List.of(), TaskNodeStatus.OUTCOME_UNKNOWN)
                .withToolStatus(ToolStatus.UNKNOWN).withError("结果未知，需要核验");
        StateSnapshot snapshot = new StateSnapshot(1, "s", "e", null,
                new TaskGraph(List.of(unknown)), Map.of(), Map.of(), List.of(), 0);

        StateSnapshot restored = PlannerStateJson.snapshotFromJson(
                PlannerStateJson.snapshotToJson(snapshot));

        assertEquals(TaskNodeStatus.OUTCOME_UNKNOWN, restored.graph().byId("n1").status());
        assertEquals(ToolStatus.UNKNOWN, restored.graph().byId("n1").toolStatus());
        assertEquals("结果未知，需要核验", restored.graph().byId("n1").lastError());
        assertTrue(restored.graph().normalizeForScheduling().readyNodes().isEmpty());
    }

    @Test
    void legacySnapshotWithoutToolStatusStillDeserializes() throws Exception {
        TaskNode pending = node("n1", List.of(), TaskNodeStatus.PENDING);
        StateSnapshot snapshot = new StateSnapshot(1, "s", "e", null,
                new TaskGraph(List.of(pending)), Map.of(), Map.of(), List.of(), 0);
        ObjectNode json = (ObjectNode) PlannerStateJson.MAPPER.readTree(
                PlannerStateJson.snapshotToJson(snapshot));
        ((ObjectNode) json.path("graph").path("nodes").get(0)).remove("toolStatus");

        StateSnapshot restored = PlannerStateJson.snapshotFromJson(json.toString());

        assertNull(restored.graph().byId("n1").toolStatus());
        assertEquals(TaskNodeStatus.READY,
                restored.graph().normalizeForScheduling().byId("n1").status());
    }

    private static TaskNode node(String id, List<String> dependencies, TaskNodeStatus status) {
        return new TaskNode(id, "执行 " + id, "web", dependencies, List.of(), List.of(),
                status, 10, DoneWhen.note(), "", "", 0, "");
    }
}
