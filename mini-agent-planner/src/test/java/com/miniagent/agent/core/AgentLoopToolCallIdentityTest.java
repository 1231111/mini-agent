package com.miniagent.agent.core;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AgentLoopToolCallIdentityTest {

    @Test
    void malformedToolCallIdsAreReplacedWithUniqueIds() {
        AiMessage original = AiMessage.builder()
                .text("tool planning")
                .thinking("reasoning")
                .attributes(Map.of("provider", "test"))
                .toolExecutionRequests(List.of(
                        call("duplicate", "first"),
                        call("duplicate", "second"),
                        call("  ", "blank"),
                        call("stable", "valid")))
                .build();

        AiMessage normalized = AgentLoop.normalizeToolCallIds(original);

        List<ToolExecutionRequest> calls = normalized.toolExecutionRequests();
        List<String> ids = calls.stream().map(ToolExecutionRequest::id).toList();
        assertEquals(4, Set.copyOf(ids).size());
        assertTrue(ids.stream().allMatch(id -> id != null && !id.isBlank()));
        assertEquals("duplicate", ids.getFirst(), "the first valid occurrence keeps its provider ID");
        assertEquals("stable", ids.getLast());
        assertEquals(List.of("first", "second", "blank", "valid"),
                calls.stream().map(ToolExecutionRequest::name).toList());
        assertEquals("tool planning", normalized.text());
        assertEquals("reasoning", normalized.thinking());
        assertEquals("test", normalized.attribute("provider", String.class));
        assertNotSame(original, normalized);
    }

    @Test
    void validToolCallIdsKeepTheOriginalMessage() {
        AiMessage original = AiMessage.from(List.of(call("one", "read_file"), call("two", "read_file")));

        assertSame(original, AgentLoop.normalizeToolCallIds(original));
    }

    @Test
    void recoveryDropsIncompleteBatchesAndOrphanResultsButKeepsUserIntent() {
        UserMessage user = UserMessage.from("keep this instruction");
        AiMessage incomplete = AiMessage.from(List.of(call("unfinished", "write_file")));
        ToolExecutionResultMessage orphan = ToolExecutionResultMessage.from("orphan", "read_file", "old result");
        List<ChatMessage> history = new ArrayList<>(List.of(
                user,
                incomplete,
                AiMessage.from("assistant chatter"),
                orphan));

        List<ChatMessage> retained = AgentLoop.recoveryHistory(history);

        assertTrue(retained.contains(user));
        assertFalse(retained.contains(incomplete));
        assertFalse(retained.contains(orphan));
        assertTrue(retained.stream().noneMatch(ToolExecutionResultMessage.class::isInstance));
    }

    @Test
    void recoveryDropsHistoricalBatchesWithBlankOrDuplicateIds() {
        AiMessage malformed = AiMessage.from(List.of(
                call("", "read_file"),
                call("same", "read_file"),
                call("same", "write_file")));
        List<ChatMessage> history = new ArrayList<>(List.of(
                UserMessage.from("preserve goal"),
                malformed,
                ToolExecutionResultMessage.from("", "read_file", "blank result"),
                ToolExecutionResultMessage.from("same", "read_file", "ambiguous result"),
                ToolExecutionResultMessage.from("same", "write_file", "ambiguous result")));

        List<ChatMessage> retained = AgentLoop.recoveryHistory(history);

        assertTrue(retained.stream().anyMatch(message -> message instanceof UserMessage user
                && user.singleText().equals("preserve goal")));
        assertFalse(retained.contains(malformed));
        assertTrue(retained.stream().noneMatch(ToolExecutionResultMessage.class::isInstance));
    }

    @Test
    void recoveryPairsIdsWithinEachBatchWhenAProviderReusesIdsAcrossTurns() {
        AiMessage first = AiMessage.from(List.of(call("call_1", "read_file")));
        ToolExecutionResultMessage firstResult = ToolExecutionResultMessage.from("call_1", "read_file", "first");
        AiMessage second = AiMessage.from(List.of(call("call_1", "read_file")));
        ToolExecutionResultMessage secondResult = ToolExecutionResultMessage.from("call_1", "read_file", "second");
        List<ChatMessage> history = new ArrayList<>(List.of(
                UserMessage.from("read both files"), first, firstResult, second, secondResult));

        List<ChatMessage> retained = AgentLoop.recoveryHistory(history);

        assertTrue(retained.contains(first));
        assertTrue(retained.contains(firstResult));
        assertTrue(retained.contains(second));
        assertTrue(retained.stream().anyMatch(message -> message instanceof ToolExecutionResultMessage result
                && result.text().equals("second")), retained.toString());
    }

    private static ToolExecutionRequest call(String id, String name) {
        return ToolExecutionRequest.builder().id(id).name(name).arguments("{}").build();
    }
}
