package com.miniagent.agent.planner;

import com.miniagent.agent.scope.TaskBoundary;
import com.miniagent.agent.scope.TaskScopeRegistry;
import com.miniagent.agent.tool.CapabilityRegistry;
import com.miniagent.agent.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RecoveryExecutionIdentityTest {
    @Test void anOldDiagnosisCannotRepairANewExecutionWithTheSameVersionAndNodeId() {
        PlannerStateStore states = new PlannerStateStore();
        RecoveryEngine recovery = recovery(states);
        StateSnapshot old = states.init("chat", "old-execution", null, graph(TaskNodeStatus.FAILED));
        FailureDiagnosis diagnosis = recovery.diagnose(old.graph().byId("node"), "web_search", "invalid argument");
        StateSnapshot replacement = states.init("chat", "new-execution", null, graph(TaskNodeStatus.READY));
        assertEquals(old.version(), replacement.version());
        assertTrue(recovery.recover("chat", old, diagnosis).isEmpty());
        assertEquals(replacement, states.get("chat").orElseThrow());
        assertTrue(states.events("chat").stream().noneMatch(event -> event.type() == DomainEventType.RECOVERY_APPLIED));
    }

    @Test void aChangedTaskScopeCannotReuseAnOldDiagnosisEvenWhenExecutionIdsMatch() {
        TaskScopeRegistry scopes = new TaskScopeRegistry();
        PlannerStateStore states = new PlannerStateStore(scopes);
        RecoveryEngine recovery = recovery(states);
        StateSnapshot old = states.init("chat", "same-execution", null, graph(TaskNodeStatus.FAILED));
        scopes.resolve("chat", TaskBoundary.NEW, "new task");
        StateSnapshot replacement = states.init("chat", "same-execution", null, graph(TaskNodeStatus.READY));
        assertNotEquals(old.sessionId(), replacement.sessionId());
        assertTrue(recovery.recover("chat", old, recovery.diagnose(old.graph().byId("node"), "web_search", "invalid argument")).isEmpty());
        assertEquals(replacement, states.get("chat").orElseThrow());
    }

    @Test void aRevisedPlanAndAnAlreadySettledNodeRejectTheEarlierDiagnosis() {
        for (boolean revise : List.of(false, true)) {
            PlannerStateStore states = new PlannerStateStore();
            RecoveryEngine recovery = recovery(states);
            StateSnapshot old = states.init("chat", "execution", null, graph(TaskNodeStatus.FAILED));
            StateSnapshot next = revise ? old.revisePlan(graph(TaskNodeStatus.READY), old.goal(), "new plan")
                    : old.withGraph(graph(TaskNodeStatus.SUCCESS));
            StateSnapshot replacement = states.commit("chat", old.version(), next);
            assertTrue(recovery.recover("chat", old,
                    recovery.diagnose(old.graph().byId("node"), "web_search", "invalid argument")).isEmpty());
            assertEquals(replacement, states.get("chat").orElseThrow());
        }
    }

    @Test void aCurrentSnapshotStillAllowsOneRecoveryAndCannotBeReused() {
        PlannerStateStore states = new PlannerStateStore();
        RecoveryEngine recovery = recovery(states);
        StateSnapshot current = states.init("chat", "execution", null, graph(TaskNodeStatus.FAILED));
        FailureDiagnosis diagnosis = recovery.diagnose(current.graph().byId("node"), "web_search", "invalid argument");
        StateSnapshot recovered = recovery.recover("chat", current, diagnosis).orElseThrow();
        assertEquals(TaskNodeStatus.PENDING, recovered.graph().byId("node").status());
        assertEquals(1, recovered.graph().byId("node").retryCount());
        assertTrue(recovery.recover("chat", current, diagnosis).isEmpty());
        assertEquals(recovered, states.get("chat").orElseThrow());
    }

    @Test void aMissingExpectedIdentityCannotFallBackToTheCurrentTask() {
        PlannerStateStore states = new PlannerStateStore();
        RecoveryEngine recovery = recovery(states);
        StateSnapshot current = states.init("chat", "execution", null, graph(TaskNodeStatus.FAILED));
        assertTrue(recovery.recover("chat", null,
                recovery.diagnose(current.graph().byId("node"), "web_search", "invalid argument")).isEmpty());
        assertEquals(current, states.get("chat").orElseThrow());
    }

    private static RecoveryEngine recovery(PlannerStateStore states) {
        return new RecoveryEngine(states, new CapabilityRegistry(new ToolRegistry()), new PlannerProperties());
    }

    private static TaskGraph graph(TaskNodeStatus status) {
        return new TaskGraph(List.of(new TaskNode("node", "Lookup", "web", List.of(), List.of(), List.of(),
                status, 0, DoneWhen.note(), "", "", 0, "")));
    }
}
