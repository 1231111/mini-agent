package com.miniagent.agent.planner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionEvidenceContractTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void aClaimCannotManufactureACompletedSearchOrRead() throws Exception {
        JsonNode evidence = JSON.readTree(ExecutionEvidence.build(
                "I ran search_code and read_file and all checks passed.", List.of()));

        assertThat(evidence.path("claims").asText()).contains("search_code");
        assertThat(evidence.path("observations").isEmpty()).isTrue();
        assertThat(evidence.path("callCount").asInt()).isZero();
        assertThat(evidence.path("complete").asBoolean()).isFalse();
    }

    @Test
    void recordsSearchBeforeReadWithRealArgumentsAndReturnStatus() throws Exception {
        List<ChatMessage> messages = List.of(UserMessage.from("Inspect the game"),
                call("search", "search_code", "{\"path\":\"index.html\",\"pattern\":\"setAnimationLoop\"}"),
                result("search", "search_code", "index.html:6202:setAnimationLoop"),
                call("read", "read_file", "{\"path\":\"index.html\",\"offset\":6202,\"limit\":1}"),
                result("read", "read_file", "[read_file path=C:/ws/index.html line_offset=6202 char_offset=0 partial=true]\n6202|setAnimationLoop()"));

        JsonNode evidence = JSON.readTree(ExecutionEvidence.build("Located the entry.", messages));
        JsonNode search = evidence.path("observations").get(0);
        JsonNode read = evidence.path("observations").get(1);
        assertThat(evidence.path("pairedCount").asInt()).isEqualTo(2);
        assertThat(evidence.path("complete").asBoolean()).isTrue();
        assertThat(search.path("toolName").asText()).isEqualTo("search_code");
        assertThat(search.path("success").asBoolean()).isTrue();
        assertThat(search.path("arguments").asText()).contains("setAnimationLoop");
        assertThat(search.path("resultMessageIndex").asInt()).isLessThan(read.path("callMessageIndex").asInt());
        assertThat(read.path("receipt").asText()).contains("6202|setAnimationLoop");
    }

    @Test
    void parallelReturnOrderDoesNotRewriteInvocationOrder() throws Exception {
        AiMessage batch = AiMessage.builder().toolExecutionRequests(List.of(
                request("first", "read_file", "{\"path\":\"a.txt\"}"),
                request("second", "read_file", "{\"path\":\"b.txt\"}"))).build();
        JsonNode evidence = JSON.readTree(ExecutionEvidence.build("", List.of(batch,
                result("second", "read_file", "B"), result("first", "read_file", "A"))));

        JsonNode first = evidence.path("observations").get(0);
        JsonNode second = evidence.path("observations").get(1);
        assertThat(first.path("sequence").asInt()).isZero();
        assertThat(first.path("batch").asInt()).isEqualTo(second.path("batch").asInt());
        assertThat(first.path("resultMessageIndex").asInt()).isGreaterThan(second.path("resultMessageIndex").asInt());
    }

    @Test
    void rejectsUnpairedMismatchedAndDuplicateReturnReceipts() throws Exception {
        List<ChatMessage> messages = List.of(
                result("future", "search_code", "a fabricated early return"),
                call("future", "search_code", "{}"),
                call("mismatch", "read_file", "{}"),
                result("mismatch", "search_code", "wrong name"),
                call("ok", "read_file", "{}"),
                result("ok", "read_file", "real"),
                result("ok", "read_file", "replayed"));
        JsonNode evidence = JSON.readTree(ExecutionEvidence.build("", messages));

        assertThat(evidence.path("pairedCount").asInt()).isEqualTo(1);
        assertThat(evidence.path("unpairedCallCount").asInt()).isEqualTo(2);
        assertThat(evidence.path("unpairedResultCount").asInt()).isEqualTo(3);
        assertThat(evidence.path("complete").asBoolean()).isFalse();
        assertThat(evidence.path("observations").get(0).path("receipt").asText()).isEqualTo("real");
    }

    @Test
    void failedToolIsNotRecordedAsSuccessfulAndSecretsAreRedacted() throws Exception {
        String args = "{\"path\":\"index.html\",\"authorization\":\"Bearer test-private-token\"}";
        JsonNode evidence = JSON.readTree(ExecutionEvidence.build("", List.of(
                call("failure", "read_file", args),
                result("failure", "read_file", "{\"success\":false,\"errorCode\":\"NOT_FOUND\",\"error\":\"file missing\"}"))));
        JsonNode observation = evidence.path("observations").get(0);

        assertThat(observation.path("success").asBoolean()).isFalse();
        assertThat(observation.path("errorCode").asText()).isEqualTo("NOT_FOUND");
        assertThat(observation.path("arguments").asText()).contains("REDACTED").doesNotContain("test-private-token");
    }

    @Test
    void excludesOldNodesAndTodoClaimsFromToolObservations() throws Exception {
        JsonNode evidence = JSON.readTree(ExecutionEvidence.build("current claim", List.of(
                call("old", "search_code", "{}"), result("old", "search_code", "old result"),
                UserMessage.from("Current node"),
                call("submit", "todo", "{\"action\":\"submit\",\"id\":2}"),
                result("submit", "todo", "{\"success\":true,\"evidence\":\"I searched\"}"))));

        assertThat(evidence.path("callCount").asInt()).isZero();
        assertThat(evidence.path("observations").isEmpty()).isTrue();
        assertThat(evidence.toString()).doesNotContain("old result", "I searched");
    }

    @Test
    void hugeClaimsAndToolReturnsRemainBoundedValidAndExplicitlyIncomplete() throws Exception {
        List<ChatMessage> messages = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            messages.add(call("read-" + i, "read_file", "{\"path\":\"index.html\",\"offset\":" + i + "}"));
            messages.add(result("read-" + i, "read_file", "x".repeat(10_000)));
        }
        String encoded = ExecutionEvidence.build("c".repeat(20_000), messages);
        JsonNode evidence = JSON.readTree(encoded);

        assertThat(encoded.length()).isLessThanOrEqualTo(11_200);
        assertThat(evidence.path("pairedCount").asInt()).isEqualTo(24);
        assertThat(evidence.path("claimsTruncated").asBoolean()).isTrue();
        assertThat(evidence.path("truncated").asBoolean()).isTrue();
        assertThat(evidence.path("complete").asBoolean()).isFalse();
        assertThat(evidence.path("omittedObservationCount").asInt()).isPositive();
    }

    @Test
    void persistedEnvelopeRetainsObservationIdentityOrderAndIncompleteMetadata() throws Exception {
        String envelope = ExecutionEvidence.build("entry found", List.of(
                call("search", "search_code", "{\"path\":\"index.html\"}"),
                result("search", "search_code", "index.html:6202:start"),
                call("missing", "read_file", "{}")));
        ExecutionEvidence parsed = ExecutionEvidence.fromEnvelope(envelope);

        assertThat(parsed.modelClaims()).isEqualTo("entry found");
        assertThat(parsed.observations()).hasSize(1);
        assertThat(parsed.observations().get(0).toolName()).isEqualTo("search_code");
        assertThat(parsed.observations().get(0).resultMessageIndex()).isGreaterThan(
                parsed.observations().get(0).callMessageIndex());
        assertThat(parsed.completeness().complete()).isFalse();
        assertThat(parsed.completeness().unpairedCallCount()).isEqualTo(1);
        assertThat(parsed.completeness().callCount()).isEqualTo(2);
    }

    @Test
    void malformedDuplicateAndOversizedEnvelopesCannotCreateObservations() {
        String malformed = "{\"kind\":\"EXECUTION_EVIDENCE\",\"observations\":[";
        ExecutionEvidence incomplete = ExecutionEvidence.fromEnvelope(malformed);
        assertThat(incomplete.observations()).isEmpty();
        assertThat(incomplete.modelClaims()).isEqualTo(malformed);
        assertThat(incomplete.completeness().complete()).isFalse();

        String encoded = ExecutionEvidence.build("claim", List.of(
                call("read", "read_file", "{}"), result("read", "read_file", "real")));
        String duplicateKind = encoded.substring(0, encoded.length() - 1) + ",\"kind\":\"EXECUTION_EVIDENCE\"}";
        assertThat(ExecutionEvidence.fromEnvelope(duplicateKind).observations()).isEmpty();
        assertThat(ExecutionEvidence.fromEnvelope(encoded + " trailing").observations()).isEmpty();
        ExecutionEvidence oversized = ExecutionEvidence.fromEnvelope("x".repeat(48_001));
        assertThat(oversized.observations()).isEmpty();
        assertThat(oversized.modelClaims().length()).isLessThanOrEqualTo(24_000);
        assertThat(oversized.completeness().truncated()).isTrue();
    }

    @Test
    void moreThan128RealReceiptsAreBoundedWithoutFailingTheRun() throws Exception {
        List<ChatMessage> messages = new ArrayList<>();
        for (int i = 0; i < 150; i++) {
            messages.add(call("read" + i, "read_file", "{}"));
            messages.add(result("read" + i, "read_file", "receipt" + i));
        }
        ExecutionEvidence captured = ExecutionEvidence.fromMessages("", messages);
        assertThat(captured.observations()).hasSize(128);
        assertThat(captured.completeness().pairedCount()).isEqualTo(150);
        assertThat(captured.completeness().omittedObservationCount()).isEqualTo(22);
        assertThat(captured.completeness().truncated()).isTrue();
        String envelope = ExecutionEvidence.build("\"".repeat(8_001), messages);
        assertThat(envelope.length()).isLessThanOrEqualTo(11_200);
        ExecutionEvidence parsed = ExecutionEvidence.fromEnvelope(envelope);
        assertThat(parsed.completeness().pairedCount()).isEqualTo(150);
        assertThat(parsed.completeness().omittedObservationCount()).isPositive();
        assertThat(parsed.completeness().complete()).isFalse();
    }

    @Test
    void semanticEvidenceWrapsClaimsButKeepsNamedOutputBindingsUnchanged() throws Exception {
        TaskNode node = new TaskNode("inspect", "Inspect game entry", "file_read", List.of(), List.of(),
                List.of("game-structure"), TaskNodeStatus.RUNNING, 1,
                new DoneWhen("note", "", "", List.of("Entry functions and line numbers")), "", "", 0, "");
        String claim = "Entry startGame() at index.html:42";
        NodeOutputBinder.Bound bound = NodeOutputBinder.bind(node, "", List.of(
                call("read", "read_file", "{\"path\":\"index.html\"}"),
                result("read", "read_file", "42|function startGame() {}")), "", null, "", 2, claim);

        assertThat(JSON.readTree(bound.evidence()).path("observations").size()).isEqualTo(1);
        assertThat(JSON.readTree(bound.evidence()).path("claims").asText()).isEqualTo(claim);
        assertThat(bound.bindings()).containsEntry("game-structure", claim);
    }

    private static ToolExecutionRequest request(String id, String tool, String args) {
        return ToolExecutionRequest.builder().id(id).name(tool).arguments(args).build();
    }

    private static AiMessage call(String id, String tool, String args) {
        return AiMessage.builder().toolExecutionRequests(List.of(request(id, tool, args))).build();
    }

    private static ToolExecutionResultMessage result(String id, String tool, String value) {
        return ToolExecutionResultMessage.from(id, tool, value);
    }
}
