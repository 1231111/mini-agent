package com.miniagent.agent.planner;

import com.miniagent.agent.scope.TaskBoundary;
import com.miniagent.agent.scope.TaskScopeRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlannerDispatchFenceTest {
    @Test void matchesThePreviousFenceForEveryNodeStatusAndBothLegacyAndCurrentRevisions() {
        for (TaskNodeStatus status : TaskNodeStatus.values()) {
            StateSnapshot state = snapshot("chat", "execution", status);
            ActionProposal proposal = proposal(state);
            boolean before = PlanningLoop.ownsRunningProposal(state, proposal);
            assertEquals(before, PlannerDispatchFence.matches(state, "chat", proposal), status.name());
            assertEquals(before, PlannerDispatchFence.matchesJson(PlannerStateJson.snapshotToJson(state),
                    "execution", "chat", proposal), status.name());
        }
        StateSnapshot initial = snapshot("chat", null, TaskNodeStatus.RUNNING);
        String legacy = PlannerStateJson.snapshotToJson(initial);
        try {
            var tree = (com.fasterxml.jackson.databind.node.ObjectNode) PlannerStateJson.MAPPER.readTree(legacy);
            tree.remove("planRevision");
            assertTrue(PlannerDispatchFence.matchesJson(tree.toString(), null, "chat", proposal(initial)));
        } catch (Exception failure) { throw new AssertionError(failure); }
    }

    @Test void rejectsReplacementPlanChangeMissingNodesAndMismatchedMetadata() {
        StateSnapshot state = snapshot("chat", "execution", TaskNodeStatus.RUNNING);
        ActionProposal proposal = proposal(state);
        assertFalse(PlannerDispatchFence.matches(snapshot("chat", "replacement", TaskNodeStatus.RUNNING), "chat", proposal));
        assertFalse(PlannerDispatchFence.matches(state.revisePlan(state.graph(), state.goal(), "changed"), "chat", proposal));
        assertFalse(PlannerDispatchFence.matches(state.withGraph(new TaskGraph(List.of())), "chat", proposal));
        assertFalse(PlannerDispatchFence.matches(state, "different-scope", proposal));
        assertFalse(PlannerDispatchFence.matchesJson(PlannerStateJson.snapshotToJson(state), "replacement", "chat", proposal));
        assertFalse(PlannerDispatchFence.matches(state, "chat", new ActionProposal("empty", 1, "execution", List.of())));
    }

    @Test void scansTheEntireSnapshotAndKeepsDomainConstructorValidation() throws Exception {
        StateSnapshot state = snapshot("chat", "execution", TaskNodeStatus.RUNNING);
        String json = PlannerStateJson.snapshotToJson(state);
        ActionProposal proposal = proposal(state);
        assertFalse(PlannerDispatchFence.matchesJson(json + " trailing", "execution", "chat", proposal));
        assertFalse(PlannerDispatchFence.matchesJson(json + " {}", "execution", "chat", proposal));
        assertTrue(PlannerDispatchFence.matchesJson(json + " \n\t", "execution", "chat", proposal));
        assertFalse(PlannerDispatchFence.matchesJson(json.substring(0, json.length() - 1), "execution", "chat", proposal));
        assertFalse(PlannerDispatchFence.matchesJson(json.replace("\"sessionId\":\"chat\"",
                "\"sessionId\":\"chat\",\"sessionId\":\"chat\""), "execution", "chat", proposal));
        assertFalse(PlannerDispatchFence.matchesJson(json.replace("\"executionId\":\"execution\"",
                "\"executionId\":\"execution\",\"executionId\":\"execution\""), "execution", "chat", proposal));
        assertFalse(PlannerDispatchFence.matchesJson(json.replace("\"status\":\"RUNNING\"",
                "\"status\":\"NOT_A_STATUS\""), "execution", "chat", proposal));
        var tree = (com.fasterxml.jackson.databind.node.ObjectNode) PlannerStateJson.MAPPER.readTree(json);
        ((com.fasterxml.jackson.databind.node.ObjectNode) tree.path("graph").path("nodes").get(0))
                .put("procedureVariant", "CANDIDATE");
        assertThrows(IllegalStateException.class, () -> PlannerStateJson.snapshotFromJson(tree.toString()));
        assertFalse(PlannerDispatchFence.matchesJson(tree.toString(), "execution", "chat", proposal),
                "an invalid Procedure constructor cannot be bypassed by reading only the status");
    }

    @Test void duplicateNodeIdentifiersCannotAuthorizeAmbiguousDispatch() {
        StateSnapshot state = snapshot("chat", "execution", TaskNodeStatus.RUNNING);
        TaskNode running = state.graph().nodes().getFirst();
        StateSnapshot corrupt = state.withGraph(new TaskGraph(List.of(running, running.withStatus(TaskNodeStatus.PENDING))));
        assertFalse(PlannerDispatchFence.matches(corrupt, "chat", proposal(state)));
        assertFalse(PlannerDispatchFence.matchesJson(PlannerStateJson.snapshotToJson(corrupt), "execution", "chat", proposal(state)));
    }

    @Test void memoryCasClearAndScopeSwitchRevokeWithoutCachingPreviousAuthorization() {
        TaskScopeRegistry scopes = new TaskScopeRegistry();
        PlannerStateStore store = new PlannerStateStore(scopes);
        StateSnapshot running = store.init("chat", "execution", null, snapshot("chat", "execution", TaskNodeStatus.RUNNING).graph());
        ActionProposal proposal = proposal(running);
        assertTrue(store.dispatchFenceValid("chat", proposal));
        store.commit("chat", running.version(), running.withGraph(running.graph().replace(
                running.graph().nodes().getFirst().withStatus(TaskNodeStatus.SUCCESS))));
        assertFalse(store.dispatchFenceValid("chat", proposal));
        store.init("chat", "replacement", null, running.graph());
        assertFalse(store.dispatchFenceValid("chat", proposal));
        store.clear("chat");
        assertFalse(store.dispatchFenceValid("chat", proposal));
        store.init("chat", "execution", null, running.graph());
        assertTrue(store.dispatchFenceValid("chat", proposal));
        scopes.resolve("chat", TaskBoundary.NEW, "different task");
        assertFalse(store.dispatchFenceValid("chat", proposal));
    }

    @Test void persistentStoreUsesTheAuthoritativePortWithoutLoadingAnAuditBundle() {
        PlannerStatePersistence persistence = mock(PlannerStatePersistence.class);
        PlannerStateStore store = new PlannerStateStore(persistence, null);
        ActionProposal proposal = proposal(snapshot("chat", "execution", TaskNodeStatus.RUNNING));
        when(persistence.dispatchFenceValid("chat", proposal)).thenReturn(true, false)
                .thenThrow(new IllegalStateException("authority unavailable"));
        assertTrue(store.dispatchFenceValid("chat", proposal));
        assertFalse(store.dispatchFenceValid("chat", proposal));
        assertThrows(IllegalStateException.class, () -> store.dispatchFenceValid("chat", proposal));
        verify(persistence, never()).load(anyString());
    }

    @Test void aMultiNodeProposalRequiresEveryRequestedNodeToRemainRunning() {
        StateSnapshot initial = snapshot("chat", "execution", TaskNodeStatus.RUNNING);
        TaskNode second = new TaskNode("second", "Verify source", "web", List.of(), List.of(), List.of(),
                TaskNodeStatus.RUNNING, 0, DoneWhen.note(), "", "", 0, "");
        StateSnapshot both = initial.withGraph(new TaskGraph(List.of(initial.graph().nodes().getFirst(), second)));
        ActionProposal proposal = new GraphScheduler().propose(both, both.graph().nodes(), 2);
        assertEquals(2, proposal.actions().size());
        for (TaskNodeStatus status : TaskNodeStatus.values()) {
            StateSnapshot current = both.withGraph(both.graph().replace(second.withStatus(status)));
            boolean previous = PlanningLoop.ownsRunningProposal(current, proposal);
            assertEquals(previous, PlannerDispatchFence.matches(current, "chat", proposal), status.name());
            assertEquals(previous, PlannerDispatchFence.matchesJson(PlannerStateJson.snapshotToJson(current),
                    "execution", "chat", proposal), status.name());
        }
    }

    @Test void defaultFileFallbackRechecksCurrentPersistedStateOnEachCall(@TempDir Path directory) {
        PlannerStatePersistence persistence = new FilePlannerStatePersistence(directory.toString());
        StateSnapshot running = snapshot("chat", "execution", TaskNodeStatus.RUNNING);
        ActionProposal proposal = proposal(running);
        persistence.replace("chat", running, List.of());
        assertTrue(persistence.dispatchFenceValid("chat", proposal));
        StateSnapshot completed = running.withGraph(running.graph().replace(
                running.graph().nodes().getFirst().withStatus(TaskNodeStatus.SUCCESS))).bumpVersion();
        assertTrue(persistence.compareAndSetAppending("chat", running.version(), "execution", completed, List.of()));
        assertFalse(persistence.dispatchFenceValid("chat", proposal));
        persistence.replace("chat", snapshot("chat", "replacement", TaskNodeStatus.RUNNING), List.of());
        assertFalse(persistence.dispatchFenceValid("chat", proposal));
        persistence.delete("chat");
        assertFalse(persistence.dispatchFenceValid("chat", proposal));
    }

    private static StateSnapshot snapshot(String session, String execution, TaskNodeStatus status) {
        TaskNode node = new TaskNode("node", "Check evidence", "web", List.of(), List.of(), List.of(), status,
                0, DoneWhen.note(), "", "", 0, "");
        return new StateSnapshot(1, session, execution, null, new TaskGraph(List.of(node)), Map.of(), Map.of(), List.of(), 0);
    }

    private static ActionProposal proposal(StateSnapshot state) {
        return new GraphScheduler().propose(state, state.graph().nodes(), 1);
    }
}
