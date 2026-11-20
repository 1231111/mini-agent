package com.miniagent.agent.core;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.SystemMessage;
import com.miniagent.common.model.EffectiveModelContext;
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
        assertTrue(retained.stream().anyMatch(message -> message instanceof SystemMessage system
                && system.text().contains("先重新读取文件或观察外部状态")));
    }

    @Test
    void preflightKeepsOnlyCompleteUnambiguousBatchesAndDropsAllMalformedToolRoles() {
        UserMessage user = UserMessage.from("keep original user intent");
        AiMessage valid = AiMessage.builder().thinking("preserve this reasoning")
                .toolExecutionRequests(List.of(call("read", "read_file"), call("write", "write_file"))).build();
        ToolExecutionResultMessage validWrite = ToolExecutionResultMessage.from("write", "write_file", "done");
        ToolExecutionResultMessage validRead = ToolExecutionResultMessage.from("read", "read_file", "content");
        AiMessage duplicateIds = AiMessage.from(List.of(call("duplicate", "read_file"),
                call("duplicate", "write_file")));
        AiMessage mismatchedResult = AiMessage.from(List.of(call("mismatch", "write_file")));
        AiMessage incompleteBatch = AiMessage.from(List.of(call("first", "read_file"),
                call("second", "write_file")));
        List<ChatMessage> history = new ArrayList<>(List.of(
                user,
                valid, validWrite, validRead,
                duplicateIds,
                ToolExecutionResultMessage.from("duplicate", "read_file", "ambiguous one"),
                ToolExecutionResultMessage.from("duplicate", "write_file", "ambiguous two"),
                mismatchedResult,
                ToolExecutionResultMessage.from("mismatch", "read_file", "wrong tool"),
                incompleteBatch,
                ToolExecutionResultMessage.from("first", "read_file", "partial batch"),
                UserMessage.from("separator"),
                ToolExecutionResultMessage.from("orphan", "read_file", "orphan result")));

        AgentLoop.ToolHistoryPreflight result = AgentLoop.preflightToolMessages(history);

        assertTrue(result.changed());
        assertEquals(3, result.droppedCallMessages());
        assertEquals(5, result.droppedResultMessages());
        assertTrue(result.messages().contains(user));
        assertTrue(result.messages().contains(valid));
        assertTrue(result.messages().getFirst() instanceof SystemMessage,
                "the recovery instruction must precede provider conversation history");
        assertEquals("preserve this reasoning", result.messages().stream()
                .filter(AiMessage.class::isInstance).map(AiMessage.class::cast)
                .filter(message -> message.hasToolExecutionRequests()).findFirst().orElseThrow().thinking());
        assertTrue(result.messages().contains(validWrite), "results may be returned in any order when IDs match");
        assertTrue(result.messages().contains(validRead));
        assertEquals(List.of(valid), result.messages().stream().filter(AiMessage.class::isInstance)
                .map(AiMessage.class::cast).toList(), "only the intact batch should remain as assistant tool calls");
        assertEquals(2, result.messages().stream().filter(ToolExecutionResultMessage.class::isInstance).count());
        SystemMessage warning = result.messages().stream().filter(SystemMessage.class::isInstance)
                .map(SystemMessage.class::cast).filter(system -> system.text().contains("工具调用历史不完整"))
                .findFirst().orElseThrow();
        assertTrue(warning.text().length() < 300);
        assertFalse(warning.text().contains("ambiguous one"));
        assertFalse(warning.text().contains("orphan"));

        AgentLoop.ToolHistoryPreflight secondPass = AgentLoop.preflightToolMessages(result.messages());
        assertFalse(secondPass.changed(), "preflight should be idempotent");
        assertEquals(1, secondPass.messages().stream().filter(SystemMessage.class::isInstance)
                .map(SystemMessage.class::cast)
                .filter(system -> system.text().contains("工具调用历史不完整")).count());
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

    @Test
    void deepseekPreflightDropsPersistedToolCallsWithoutRequiredReasoningContent() {
        EffectiveModelContext.set(null, null, 64_000, true);
        try {
            AiMessage oldPersistedCall = AiMessage.from(call("old", "write_file"));
            ToolExecutionResultMessage oldResult = ToolExecutionResultMessage.from("old", "write_file", "written");

            AgentLoop.ToolHistoryPreflight result = AgentLoop.preflightToolMessages(
                    List.of(UserMessage.from("continue"), oldPersistedCall, oldResult));

            assertEquals(1, result.droppedCallMessages());
            assertEquals(1, result.droppedResultMessages());
            assertTrue(result.messages().stream().noneMatch(AiMessage.class::isInstance));
            assertTrue(result.messages().stream().anyMatch(message -> message instanceof SystemMessage system
                    && system.text().contains("重新读取文件或观察外部状态")));
        } finally {
            EffectiveModelContext.clear();
        }
    }

    private static ToolExecutionRequest call(String id, String name) {
        return ToolExecutionRequest.builder().id(id).name(name).arguments("{}").build();
    }
}
