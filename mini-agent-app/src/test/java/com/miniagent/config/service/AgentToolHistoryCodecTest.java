package com.miniagent.config.service;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AgentToolHistoryCodecTest {

    @Test
    void roundTripsOnlyCompleteToolCallAndResultPairsAfterLatestUserTurn() {
        AiMessage oldCall = AiMessage.from(call("old", "read_file", "{}"));
        ToolExecutionResultMessage oldResult = ToolExecutionResultMessage.from("old", "read_file", "old data");
        AiMessage currentCall = AiMessage.builder().text("reading").toolExecutionRequests(
                List.of(call("current", "read_file", "{\"path\":\"a.txt\"}"))).build();
        ToolExecutionResultMessage currentResult = ToolExecutionResultMessage.from(
                "current", "read_file", "file contents");
        AiMessage incomplete = AiMessage.from(call("unfinished", "write_file", "{}"));
        List<ChatMessage> run = List.of(
                UserMessage.from("old request"), oldCall, oldResult,
                UserMessage.from("current request"), currentCall, currentResult,
                incomplete);

        String stored = AgentToolHistoryCodec.encodeLatestTurn(run);
        List<ChatMessage> restored = AgentToolHistoryCodec.decode(stored);

        assertEquals(2, restored.size());
        AiMessage restoredCall = assertInstanceOf(AiMessage.class, restored.getFirst());
        assertEquals("current", restoredCall.toolExecutionRequests().getFirst().id());
        assertEquals("{\"path\":\"a.txt\"}", restoredCall.toolExecutionRequests().getFirst().arguments());
        ToolExecutionResultMessage restoredResult = assertInstanceOf(
                ToolExecutionResultMessage.class, restored.getLast());
        assertEquals("current", restoredResult.id());
        assertEquals("file contents", restoredResult.text());
    }

    @Test
    void malformedOrMismatchedStoredBatchesAreDropped() {
        String malformed = "[{\"type\":\"assistant_tool_calls\","
                + "\"calls\":[{\"id\":\"x\",\"name\":\"read_file\",\"arguments\":\"{}\"}],"
                + "\"results\":[{\"id\":\"x\",\"name\":\"write_file\",\"text\":\"bad\"}]}]";

        assertTrue(AgentToolHistoryCodec.decode(malformed).isEmpty());
        assertTrue(AgentToolHistoryCodec.decode("not json").isEmpty());
    }

    @Test
    void encodesMultipleDagNodeTranscriptsAndScopesReusedIdsToTheirBatch() {
        List<ChatMessage> nodeMessages = List.of(
                AiMessage.from(call("shared", "read_file", "{\"path\":\"one.txt\"}")),
                ToolExecutionResultMessage.from("shared", "read_file", "one"),
                AiMessage.from(call("shared", "read_file", "{\"path\":\"two.txt\"}")),
                ToolExecutionResultMessage.from("shared", "read_file", "two"));

        List<ChatMessage> restored = AgentToolHistoryCodec.decode(
                AgentToolHistoryCodec.encodeExchanges(nodeMessages));

        assertEquals(4, restored.size());
        assertEquals("one", assertInstanceOf(ToolExecutionResultMessage.class, restored.get(1)).text());
        assertEquals("two", assertInstanceOf(ToolExecutionResultMessage.class, restored.get(3)).text());
    }

    @Test
    void sensitiveValuesAreRedactedBeforeTheyArePersisted() {
        List<ChatMessage> run = List.of(
                UserMessage.from("fetch data"),
                AiMessage.from(call("secret-call", "http_post", "{\"password\":\"do-not-store\"}")),
                ToolExecutionResultMessage.from("secret-call", "http_post", "{\"api_key\":\"sensitive\",\"ok\":true}"));

        String stored = AgentToolHistoryCodec.encodeLatestTurn(run);

        assertNotNull(stored);
        assertFalse(stored.contains("do-not-store"));
        assertFalse(stored.contains("sensitive"));
        assertTrue(stored.contains("***"));
    }

    private static ToolExecutionRequest call(String id, String name, String arguments) {
        return ToolExecutionRequest.builder().id(id).name(name).arguments(arguments).build();
    }
}
