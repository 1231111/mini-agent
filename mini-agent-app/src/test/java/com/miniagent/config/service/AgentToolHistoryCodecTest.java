package com.miniagent.config.service;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentToolHistoryCodecTest {

    @Test
    void persistsFinalReasoningOnlyWhenTheProviderNeedsRoundTripHistory() {
        List<ChatMessage> messages = List.of(
                UserMessage.from("Continue the task"),
                AiMessage.builder().text("Completed.").thinking("opaque provider reasoning").build());

        String encoded = AgentToolHistoryCodec.encodeLatestTurn(messages, true);

        assertThat(AgentToolHistoryCodec.decodeAssistantThinking(encoded))
                .isEqualTo("opaque provider reasoning");
        assertThat(AgentToolHistoryCodec.encodeLatestTurn(messages, false)).isNull();
        assertThat(AgentToolHistoryCodec.decodeAssistantThinking("[]")).isEmpty();
    }

    @Test
    void keepsReasoningAttachedToTheCompletedToolExchange() {
        ToolExecutionRequest call = ToolExecutionRequest.builder()
                .id("call-1")
                .name("read_file")
                .arguments("{\"path\":\"notes.txt\"}")
                .build();
        List<ChatMessage> messages = List.of(
                UserMessage.from("Read notes.txt"),
                AiMessage.builder().text("").thinking("tool-call reasoning")
                        .toolExecutionRequests(List.of(call)).build(),
                ToolExecutionResultMessage.from("call-1", "read_file", "notes"),
                AiMessage.builder().text("The file says notes.").thinking("final reasoning").build());

        String encoded = AgentToolHistoryCodec.encodeLatestTurn(messages, true);
        List<ChatMessage> decoded = AgentToolHistoryCodec.decode(encoded);

        assertThat(decoded).hasSize(2);
        AiMessage restoredCall = (AiMessage) decoded.getFirst();
        assertThat(restoredCall.thinking()).isEqualTo("tool-call reasoning");
        assertThat(((ToolExecutionResultMessage) decoded.get(1)).text()).isEqualTo("notes");
        assertThat(AgentToolHistoryCodec.decodeAssistantThinking(encoded)).isEqualTo("final reasoning");
    }

    @Test
    void opaqueProviderReasoningIsNeverRedactedOrPartiallyTruncated() {
        String exactProviderValue = "opaque reasoning api_key=sk-123456789012345678901234567890";
        List<ChatMessage> messages = List.of(
                UserMessage.from("Continue"),
                AiMessage.builder().text("Done").thinking(exactProviderValue).build());

        String encoded = AgentToolHistoryCodec.encodeLatestTurn(messages, true);

        assertThat(AgentToolHistoryCodec.decodeAssistantThinking(encoded)).isEqualTo(exactProviderValue);
        assertThat(AgentToolHistoryCodec.encodeLatestTurn(List.of(
                UserMessage.from("Continue"),
                AiMessage.builder().text("Done").thinking("x".repeat(8_001)).build()), true)).isNull();
    }

    @Test
    void omitsToolExchangeWhenRequiredProviderReasoningIsMissing() {
        ToolExecutionRequest call = ToolExecutionRequest.builder()
                .id("call-2").name("read_file").arguments("{}").build();
        List<ChatMessage> messages = List.of(
                UserMessage.from("Read"),
                AiMessage.builder().text("").toolExecutionRequests(List.of(call)).build(),
                ToolExecutionResultMessage.from("call-2", "read_file", "result"));

        assertThat(AgentToolHistoryCodec.encodeLatestTurn(messages, true)).isNull();
    }

    @Test
    void legacyHistoryWithPossiblyRedactedThinkingIsNotReplayedAsValidProviderReasoning() {
        String legacy = "[{\"type\":\"assistant_tool_calls\",\"text\":\"\","
                + "\"thinking\":\"possibly truncated\",\"calls\":[{\"id\":\"call-1\","
                + "\"name\":\"read_file\",\"arguments\":\"{}\"}],"
                + "\"results\":[{\"id\":\"call-1\",\"name\":\"read_file\",\"text\":\"result\"}]}]";

        List<ChatMessage> decoded = AgentToolHistoryCodec.decode(legacy);

        assertThat(decoded).hasSize(2);
        assertThat(((AiMessage) decoded.getFirst()).thinking()).isEmpty();
        assertThat(AgentToolHistoryCodec.decodeAssistantThinking(legacy)).isEmpty();
    }
}
