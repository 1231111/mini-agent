package com.miniagent.agent.planner;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

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
    void candidateIdentitySurvivesNodeTransformsAndSnapshotRoundTrip() throws Exception {
        TaskNode candidate = new TaskNode("n1", "step", "code", List.of(), List.of(), List.of(),
                TaskNodeStatus.PENDING, 1, DoneWhen.note(), "", "", 0, "")
                .withProcedure(17L, 3)
                .withEvolutionCandidate(81L, "a".repeat(64));

        TaskNode transformed = candidate.withStatus(TaskNodeStatus.RUNNING).withRetryInc()
                .withOutput("ok").withToolHint("shell");
        assertEquals(81L, transformed.evolutionProposalId());
        assertEquals("a".repeat(64), transformed.evolutionCandidateHash());
        assertEquals("CANDIDATE", transformed.procedureVariant());
        assertEquals(0, transformed.canaryTargetPercent());

        String snapshot = new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString(new TaskGraph(List.of(candidate)));
        TaskNode decoded = PlannerStateJson.snapshotFromJson("""
                {"version":1,"sessionId":"s1","executionId":"e1","goal":null,"graph":%s}
                """.formatted(snapshot)).graph().byId("n1");
        assertEquals(81L, decoded.evolutionProposalId());
        assertEquals("a".repeat(64), decoded.evolutionCandidateHash());
        assertEquals("CANDIDATE", decoded.procedureVariant());
        assertEquals(0, decoded.canaryTargetPercent());
    }

    @Test
    void candidatePercentageAndSnapshotArePinnedToAuthorizedInput() throws Exception {
        ProcedurePromptInput authorized = new ProcedurePromptInput(17L, 3, "p", "candidate description",
                List.of("pre"), List.of("step"), List.of("done"), 81L, "b".repeat(64), "CANDIDATE", 25);
        String json = """
                {"nodes":[{"id":"n1","name":"step","capability":"code",
                "procedureId":17,"procedureVersion":3,"evolutionProposalId":999,"evolutionCandidateHash":"forged"}]}
                """;
        TaskNode node = new GoalCompiler(new PlannerProperties()).parseGraph(json, List.of(authorized)).byId("n1");
        assertEquals(81L, node.evolutionProposalId());
        assertEquals("b".repeat(64), node.evolutionCandidateHash());
        assertEquals(25, node.canaryTargetPercent());
        assertTrue(node.procedureSnapshotJson().contains("candidate description"));
    }

    @Test
    void compileUsesOneProcedureSnapshotForPromptAndNodeBinding() {
        PlannerProperties properties = new PlannerProperties();
        properties.setCompilerRetry(0);
        GoalCompiler compiler = new GoalCompiler(properties);
        AtomicInteger reads = new AtomicInteger();
        compiler.attachProcedurePromptPort(query -> {
            int read = reads.incrementAndGet();
            return List.of(new ProcedurePromptInput(17L, read == 1 ? 3 : 4,
                    "p", read == 1 ? "authorized v3" : "changed v4",
                    List.of(), List.of(read == 1 ? "step-v3" : "step-v4"), List.of(),
                    81L, "b".repeat(64), "CANDIDATE", 25));
        });
        dev.langchain4j.model.chat.ChatModel chat = org.mockito.Mockito.mock(
                dev.langchain4j.model.chat.ChatModel.class);
        org.mockito.Mockito.when(chat.chat(org.mockito.ArgumentMatchers.any(
                dev.langchain4j.model.chat.request.ChatRequest.class))).thenAnswer(invocation -> {
            var request = invocation.getArgument(0,
                    dev.langchain4j.model.chat.request.ChatRequest.class);
            assertTrue(request.messages().getLast().toString().contains("authorized v3"));
            return dev.langchain4j.model.chat.response.ChatResponse.builder()
                    .aiMessage(dev.langchain4j.data.message.AiMessage.from("""
                            {"nodes":[{"id":"n1","name":"step","capability":"code",
                            "procedureId":17,"procedureVersion":3}]}
                            """))
                    .build();
        });

        GoalCompiler.CompileResult result = compiler.compile(chat, "执行 SOP",
                com.miniagent.agent.task.TaskPlan.of("执行 SOP"));

        assertEquals(1, reads.get());
        TaskNode node = result.graph().byId("n1");
        assertEquals(3, node.procedureVersion());
        assertTrue(node.procedureSnapshotJson().contains("step-v3"));
        assertEquals(25, node.canaryTargetPercent());
    }

    @Test
    void candidateIdentityMustBeCompleteAndVariantCannotBeForged() {
        TaskNode baseline = new TaskNode("n1", "step", "code", List.of(), List.of(), List.of(),
                TaskNodeStatus.PENDING, 1, DoneWhen.note(), "", "", 0, "")
                .withProcedure(17L, 3);
        assertThrows(IllegalArgumentException.class, () -> baseline.withProcedureVariant("CANDIDATE"));
        assertThrows(IllegalArgumentException.class, () -> baseline.withEvolutionCandidate(81L, ""));
        assertThrows(IllegalArgumentException.class, () -> baseline.withEvolutionCandidate(81L, "a".repeat(64))
                .withProcedureVariant("BASELINE"));
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
