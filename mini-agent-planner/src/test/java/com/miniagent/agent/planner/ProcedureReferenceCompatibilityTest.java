package com.miniagent.agent.planner;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProcedureReferenceCompatibilityTest {
    @Test
    void oldTaskNodeConstructorAndLegacySnapshotWithoutFieldsRemainUnbound() throws Exception {
        TaskNode legacy = new TaskNode("n1", "read", "file_read", List.of(), List.of(), List.of(),
                TaskNodeStatus.PENDING, 1, DoneWhen.note(), "", "", 0, "");
        assertNull(legacy.procedureId());
        TaskNode decoded = PlannerStateJson.snapshotFromJson("""
                {"version":1,"sessionId":"s1","executionId":"e1","goal":null,
                 "graph":{"nodes":[{"id":"n1","name":"read","capability":"file_read"}]}}
                """).graph().byId("n1");
        assertNull(decoded.procedureId());
        assertNull(decoded.procedureVersion());
    }

    @Test
    void nodeTransformationsKeepProcedureReference() {
        TaskNode bound = new TaskNode("n1", "step", "code", List.of(), List.of(), List.of(),
                TaskNodeStatus.PENDING, 1, DoneWhen.note(), "", "", 0, "")
                .withProcedure(17L, 3);
        assertEquals(17L, bound.withStatus(TaskNodeStatus.SUCCESS).withOutput("ok").procedureId());
        assertEquals(3, bound.withRetryInc().procedureVersion());
    }

    @Test
    void compilerOnlyAcceptsOwnerSuppliedProcedureIdentity() throws Exception {
        String json = """
                {"nodes":[{"id":"n1","name":"step","capability":"code",
                "procedureId":17,"procedureVersion":3}]}
                """;
        GoalCompiler compiler = new GoalCompiler(new PlannerProperties());
        assertNull(compiler.parseGraph(json, List.of()).byId("n1").procedureId());
        assertEquals(17L, compiler.parseGraph(json,
                List.of(new ProcedurePromptInput(17L, 3, "p", "", List.of(), List.of(), List.of())))
                .byId("n1").procedureId());
    }
}
