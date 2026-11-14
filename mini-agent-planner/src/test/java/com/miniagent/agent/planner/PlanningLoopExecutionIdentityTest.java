package com.miniagent.agent.planner;

import org.junit.jupiter.api.Test;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlanningLoopExecutionIdentityTest {
    @Test void identicalNodeAndPlanVersionInANewTaskCannotAuthorizeAnOldProposal() {
        StateSnapshot oldTask = snapshot("old-execution");
        ActionProposal proposal = new GraphScheduler().propose(oldTask, oldTask.graph().nodes(), 1);
        assertTrue(PlanningLoop.ownsRunningProposal(oldTask, proposal));
        assertFalse(PlanningLoop.ownsRunningProposal(snapshot("new-execution"), proposal),
                "new tasks can reuse node ids and start at the same plan version");
    }

    @Test void theSameLogicalExecutionCanResumeWithoutChangingOwnershipIdentity() {
        StateSnapshot original = snapshot("same-execution");
        ActionProposal proposal = new GraphScheduler().propose(original, original.graph().nodes(), 1);
        StateSnapshot later = new StateSnapshot(7, original.sessionId(), original.executionId(), original.goal(),
                original.graph(), original.execution(), original.environment(), original.knowledgeRefs(), 0);
        assertTrue(PlanningLoop.ownsRunningProposal(later, proposal));
        assertFalse(PlanningLoop.ownsRunningProposal(original.withGraph(
                original.graph().replace(original.graph().byId("node").withStatus(TaskNodeStatus.SUCCESS))), proposal));
    }

    @Test void anOldProposalCannotMarkANewTasksMatchingReadyNodeRunning() throws Exception {
        PlannerStateStore store = new PlannerStateStore();
        StateSnapshot old = store.init("same-chat", "old-execution", null, readyGraph());
        ActionProposal proposal = new GraphScheduler().propose(old, old.graph().nodes(), 1);
        StateSnapshot replacement = store.init("same-chat", "new-execution", null, readyGraph());
        var method = PlanningLoop.class.getDeclaredMethod("markRunning", String.class, StateSnapshot.class, ActionProposal.class);
        method.setAccessible(true);
        assertEquals(replacement, method.invoke(loop(store), "same-chat", old, proposal));
        assertEquals(replacement, store.get("same-chat").orElseThrow());
        assertEquals(TaskNodeStatus.READY, replacement.graph().byId("node").status());
    }

    @Test void aCurrentProposalStillPersistsItsRunningReceipt() throws Exception {
        PlannerStateStore store = new PlannerStateStore();
        StateSnapshot current = store.init("same-chat", "current-execution", null, readyGraph());
        ActionProposal proposal = new GraphScheduler().propose(current, current.graph().nodes(), 1);
        var method = PlanningLoop.class.getDeclaredMethod("markRunning", String.class, StateSnapshot.class, ActionProposal.class);
        method.setAccessible(true);
        method.invoke(loop(store), "same-chat", current, proposal);
        StateSnapshot running = store.get("same-chat").orElseThrow();
        assertEquals(TaskNodeStatus.RUNNING, running.graph().byId("node").status());
        assertEquals(2, running.version());
    }

    @Test void anApprovalConflictCannotSilentlyApproveOrAdoptTheCurrentPlan() throws Exception {
        PlannerStateStore store = mock(PlannerStateStore.class);
        StateSnapshot approved = snapshot("old-execution");
        when(store.commit(anyString(), anyLong(), any()))
                .thenThrow(new PlannerStateStore.VersionConflictException(1, 2));
        var method = PlanningLoop.class.getDeclaredMethod("markPlanApproval", String.class, StateSnapshot.class, String.class);
        method.setAccessible(true);
        InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                () -> method.invoke(loop(store), "same-chat", approved, PlannerStateStore.PLAN_APPROVAL_APPROVED));
        assertInstanceOf(PlannerStateStore.VersionConflictException.class, failure.getCause());
        verify(store, never()).get(anyString());
    }

    private static PlanningLoop loop(PlannerStateStore store) {
        return new PlanningLoop(null, null, null, store, null, null, null, null,
                null, null, new PlannerMetrics(), null, null);
    }

    private static TaskGraph readyGraph() {
        TaskGraph graph = snapshot("unused").graph();
        return graph.replace(graph.byId("node").withStatus(TaskNodeStatus.READY));
    }

    private static StateSnapshot snapshot(String execution) {
        TaskNode node = new TaskNode("node", "write", "write_file", List.of(), List.of(), List.of(),
                TaskNodeStatus.RUNNING, 0, DoneWhen.note(), "", "", 0, "");
        return new StateSnapshot(1, "same-chat", execution, null, new TaskGraph(List.of(node)),
                Map.of(), Map.of(), List.of(), 0);
    }
}
