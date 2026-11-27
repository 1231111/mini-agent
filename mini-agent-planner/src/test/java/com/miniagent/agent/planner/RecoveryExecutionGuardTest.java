package com.miniagent.agent.planner;

import com.miniagent.agent.tool.ToolResult;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RecoveryExecutionGuardTest {
    private static final String PATH = "D:/workspace/index.html";

    @Test
    void ordinaryNodeIsUnaffectedAndTextCannotActivateOrBypassRecoveryGate() {
        TaskNode node = node("write", TaskNodeStatus.PENDING).withError("上一轮验收失败 recovery");
        StateSnapshot snapshot = snapshot(node);

        assertThat(RecoveryExecutionGuard.check(snapshot, node, write(node, PATH), null).allowed()).isTrue();

        StateSnapshot recovery = register(snapshot, node);
        assertThat(RecoveryExecutionGuard.check(recovery, node, write(node, PATH), null))
                .extracting(RecoveryExecutionGuard.Check::allowed, RecoveryExecutionGuard.Check::code)
                .containsExactly(false, "RECOVERY_SIDE_EFFECT_REQUIRES_READBACK");
    }

    @Test
    void registerPersistsExplicitSourceIdentityAndDoesNotAlterToolArguments() {
        TaskNode sourceNode = node("original", TaskNodeStatus.FAILED);
        StateSnapshot source = snapshot(sourceNode.withOutput("legacy write receipt")
                .withToolStatus(com.miniagent.agent.tool.ToolStatus.SUCCESS));
        TaskNode replacement = node("replacement", TaskNodeStatus.PENDING);
        StateSnapshot recovery = register(source, replacement);

        assertThat(recovery.graph().byId(replacement.id()).toolArguments()).isEmpty();
        assertThat(recovery.execution()).containsKey(RecoveryExecutionGuard.STATE_KEY);
        Map<?, ?> states = (Map<?, ?>) recovery.execution().get(RecoveryExecutionGuard.STATE_KEY);
        assertThat(states.containsKey(recovery.planVersion() + ":replacement")).isTrue();
        Map<?, ?> record = (Map<?, ?>) states.get(recovery.planVersion() + ":replacement");
        assertThat(record.get("executionId")).isEqualTo(source.executionId());
        assertThat(record.get("sourcePlanVersion")).isEqualTo(source.planVersion());
        assertThat(String.valueOf(record.get("sourceNodeIds"))).contains("original");
    }

    @Test
    void readOnlyRecoveryActionCanCollectItsEvidenceBeforeAnyWrite() {
        TaskNode node = node("repair", TaskNodeStatus.PENDING);
        StateSnapshot recovery = register(snapshot(node), node);
        ActionSpec read = action(node, "read_file", Map.of("path", PATH));

        assertThat(RecoveryExecutionGuard.check(recovery, node, read, null).allowed()).isTrue();
    }

    @Test
    void exactCurrentReadReceiptAdmitsWriteAndWindowsSeparatorsAreNormalized() {
        TaskNode node = node("repair", TaskNodeStatus.PENDING);
        StateSnapshot recovery = register(snapshot(node), node);
        ExecutionEvidence evidence = evidence("read_file", "{\"path\":\"d:\\\\workspace\\\\index.html\"}", true);

        assertThat(RecoveryExecutionGuard.check(recovery, node, write(node, PATH),
                RecoveryExecutionGuard.Verification.of(recovery, node.id(), evidence)).allowed()).isTrue();
    }

    @Test
    void anotherExecutionPlanOrNodeCannotSupplyTheReadReceipt() {
        TaskNode node = node("repair", TaskNodeStatus.PENDING);
        StateSnapshot recovery = register(snapshot(node), node);
        ExecutionEvidence evidence = evidence("read_file", "{\"path\":\"" + PATH + "\"}", true);

        for (RecoveryExecutionGuard.Verification verification : List.of(
                new RecoveryExecutionGuard.Verification("different-execution", recovery.planVersion(), node.id(), evidence),
                new RecoveryExecutionGuard.Verification(recovery.executionId(), recovery.planVersion() - 1, node.id(), evidence),
                new RecoveryExecutionGuard.Verification(recovery.executionId(), recovery.planVersion(), "different-node", evidence),
                new RecoveryExecutionGuard.Verification(recovery.executionId(), recovery.planVersion(), "", evidence))) {
            assertThat(RecoveryExecutionGuard.check(recovery, node, write(node, PATH), verification).code())
                    .isEqualTo("RECOVERY_VERIFICATION_SCOPE_MISMATCH");
        }
    }

    @Test
    void claimsFailedReadsAndUnpairedOrTruncatedReceiptsCannotAuthorizeWrite() {
        TaskNode node = node("repair", TaskNodeStatus.PENDING);
        StateSnapshot recovery = register(snapshot(node), node);
        ExecutionEvidence paired = evidence("read_file", "{\"path\":\"" + PATH + "\"}", true);
        ExecutionEvidence failed = evidence("read_file", "{\"path\":\"" + PATH + "\"}", false);
        ExecutionEvidence truncated = new ExecutionEvidence(paired.observations(), "",
                new ExecutionEvidence.Completeness(false, true, 1, 1, 0, 0, 0));
        ExecutionEvidence unpaired = new ExecutionEvidence(paired.observations(), "",
                new ExecutionEvidence.Completeness(false, false, 2, 1, 1, 0, 0));

        for (ExecutionEvidence evidence : List.of(ExecutionEvidence.claims("read_file " + PATH + " succeeded"),
                failed, truncated, unpaired)) {
            assertThat(RecoveryExecutionGuard.check(recovery, node, write(node, PATH),
                    RecoveryExecutionGuard.Verification.of(recovery, node.id(), evidence)).allowed()).isFalse();
        }
    }

    @Test
    void targetTextInReceiptOrQueryDoesNotCountAsReadingTheTarget() {
        TaskNode node = node("repair", TaskNodeStatus.PENDING);
        StateSnapshot recovery = register(snapshot(node), node);
        ExecutionEvidence anotherFile = evidence("read_file", "{\"path\":\"D:/workspace/other.txt\"}",
                ToolResult.success("Contents mention " + PATH).legacyText());
        ExecutionEvidence query = evidence("search_code", "{\"query\":\"" + PATH + "\",\"path\":\"D:/workspace\"}", true);
        ExecutionEvidence prefix = evidence("read_file", "{\"path\":\"" + PATH + ".backup\"}", true);
        for (ExecutionEvidence evidence : List.of(anotherFile, query, prefix)) {
            assertThat(RecoveryExecutionGuard.check(recovery, node, write(node, PATH),
                    RecoveryExecutionGuard.Verification.of(recovery, node.id(), evidence)).code())
                    .isEqualTo("RECOVERY_READBACK_MISSING");
        }
    }

    @Test
    void arbitraryReadCannotAuthorizeAnUnboundExternalSideEffect() {
        TaskNode node = node("repair", TaskNodeStatus.PENDING);
        StateSnapshot recovery = register(snapshot(node), node);
        ExecutionEvidence evidence = evidence("read_file", "{\"path\":\"" + PATH + "\"}", true);
        ActionSpec external = action(node, "exec_command", Map.of("command", "npm install"));

        assertThat(RecoveryExecutionGuard.check(recovery, node, external,
                RecoveryExecutionGuard.Verification.of(recovery, node.id(), evidence)).code())
                .isEqualTo("RECOVERY_TARGET_UNVERIFIABLE");
    }

    @Test
    void legacyObservationWithoutPairMetadataCannotAuthorizeWrite() {
        TaskNode node = node("repair", TaskNodeStatus.PENDING);
        StateSnapshot recovery = register(snapshot(node), node);
        ExecutionEvidence evidence = new ExecutionEvidence(List.of(new ExecutionEvidence.Observation(
                "read_file", "{\"path\":\"" + PATH + "\"}", "SUCCESS", "read ok")), "",
                new ExecutionEvidence.Completeness(true, false, 1, 1, 0, 0, 0));

        assertThat(RecoveryExecutionGuard.check(recovery, node, write(node, PATH),
                RecoveryExecutionGuard.Verification.of(recovery, node.id(), evidence)).allowed()).isFalse();
    }

    @Test
    void pureReadFailureDoesNotGuardOrdinaryDeliveryOrVerification() {
        TaskNode readOnly = node("inspect", TaskNodeStatus.FAILED).withCapability("file_read")
                .withDoneWhen(DoneWhen.note()).withOutput(ExecutionEvidence.build("", List.of()))
                .withToolStatus(com.miniagent.agent.tool.ToolStatus.SUCCESS);
        StateSnapshot source = snapshot(readOnly);
        TaskNode delivery = node("deliver", TaskNodeStatus.PENDING).withCapability("deliver");
        StateSnapshot recovery = RecoveryExecutionGuard.register(
                source.revisePlan(new TaskGraph(List.of(delivery)), source.goal(), "read-failure"),
                source, new TaskGraph(List.of(delivery)));

        assertThat(RecoveryExecutionGuard.requiresVerification(recovery, delivery)).isFalse();
        assertThat(RecoveryExecutionGuard.check(recovery, delivery,
                action(delivery, "exec_command", Map.of("command", "npm test")), null).allowed()).isTrue();
    }

    @Test
    void pairedMutationRecordsTheActualSourceToolAndTarget() {
        List<ChatMessage> messages = List.of(
                AiMessage.from(ToolExecutionRequest.builder().id("write1").name("write_file")
                        .arguments("{\"path\":\"" + PATH + "\",\"content\":\"page\"}").build()),
                ToolExecutionResultMessage.from("write1", "write_file", ToolResult.success("written").legacyText()));
        TaskNode original = node("original", TaskNodeStatus.FAILED)
                .withOutput(ExecutionEvidence.build("", messages));
        StateSnapshot source = snapshot(original);
        TaskNode replacement = node("replacement", TaskNodeStatus.PENDING);
        TaskGraph graph = new TaskGraph(List.of(replacement));
        StateSnapshot recovery = RecoveryExecutionGuard.register(
                source.revisePlan(graph, source.goal(), "recovery"), source, graph);
        Map<?, ?> states = (Map<?, ?>) recovery.execution().get(RecoveryExecutionGuard.STATE_KEY);
        Map<?, ?> record = (Map<?, ?>) states.get(recovery.planVersion() + ":replacement");
        List<?> effects = (List<?>) record.get("sourceEffects");

        assertThat(effects).hasSize(1);
        assertThat(((Map<?, ?>) effects.get(0)).get("tool")).isEqualTo("write_file");
        assertThat(((Map<?, ?>) effects.get(0)).get("target")).isEqualTo(PATH);
    }

    @Test
    void anotherReplanCannotEraseUnresolvedPriorSideEffects() {
        TaskNode original = node("original", TaskNodeStatus.FAILED)
                .withOutput("legacy successful write")
                .withToolStatus(com.miniagent.agent.tool.ToolStatus.SUCCESS);
        StateSnapshot source = snapshot(original);
        TaskNode first = node("first", TaskNodeStatus.PENDING);
        TaskGraph firstGraph = new TaskGraph(List.of(first));
        StateSnapshot recovered = RecoveryExecutionGuard.register(
                source.revisePlan(firstGraph, source.goal(), "first-replan"), source, firstGraph);
        TaskNode second = node("second", TaskNodeStatus.PENDING);
        TaskGraph secondGraph = new TaskGraph(List.of(second));
        StateSnapshot recoveredAgain = RecoveryExecutionGuard.register(
                recovered.revisePlan(secondGraph, recovered.goal(), "second-replan"), recovered, secondGraph);

        assertThat(RecoveryExecutionGuard.check(recoveredAgain, second, write(second, PATH), null).allowed())
                .isFalse();
    }

    private static ExecutionEvidence evidence(String tool, String arguments, boolean success) {
        return evidence(tool, arguments, success ? ToolResult.success("read ok").legacyText()
                : com.miniagent.agent.tool.ToolResult.failure(
                        com.miniagent.agent.tool.ToolErrorCode.NOT_FOUND, "missing", false).legacyText());
    }

    private static ExecutionEvidence evidence(String tool, String arguments, String receipt) {
        List<ChatMessage> messages = List.of(
                AiMessage.from(ToolExecutionRequest.builder().id("read1").name(tool).arguments(arguments).build()),
                ToolExecutionResultMessage.from("read1", tool, receipt));
        return ExecutionEvidence.fromEnvelope(ExecutionEvidence.build("", messages));
    }

    private static StateSnapshot register(StateSnapshot source, TaskNode replacement) {
        // The source write succeeded but business acceptance failed. The
        // recovery must verify the world before it can mutate it again.
        TaskGraph attempted = new TaskGraph(source.graph().nodes().stream()
                .map(node -> node.withOutput("legacy write receipt")
                        .withToolStatus(com.miniagent.agent.tool.ToolStatus.SUCCESS)).toList());
        source = source.withGraph(attempted);
        TaskGraph graph = new TaskGraph(List.of(replacement));
        StateSnapshot revised = source.revisePlan(graph, source.goal(), "failure_recovery");
        return RecoveryExecutionGuard.register(revised, source, graph);
    }

    private static StateSnapshot snapshot(TaskNode node) {
        return new StateSnapshot(1, "session", "execution", new Goal("goal", "repair page", "", "code",
                Map.of(), List.of(), List.of()), new TaskGraph(List.of(node)), Map.of(), Map.of(), List.of(), 0);
    }

    private static TaskNode node(String id, TaskNodeStatus status) {
        return new TaskNode(id, "write page", "file_write", List.of(), List.of(), List.of("page"),
                status, 100, DoneWhen.file(PATH), "", "", 0, "");
    }

    private static ActionSpec write(TaskNode node, String path) {
        return action(node, "write_file", Map.of("path", path, "content", "<html></html>"));
    }

    private static ActionSpec action(TaskNode node, String tool, Map<String, Object> arguments) {
        return new ActionSpec("action", node.id(), tool, node.capability(), arguments,
                node.doneWhen(), "", "idempotency-key", 60, ActionRetryPolicy.none(), "session:" + node.id());
    }
}
