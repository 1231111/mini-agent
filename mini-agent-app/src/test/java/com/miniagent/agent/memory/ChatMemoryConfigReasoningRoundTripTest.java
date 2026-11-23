package com.miniagent.agent.memory;

import com.miniagent.common.ChatRole;
import com.miniagent.config.service.AgentToolHistoryCodec;
import com.miniagent.config.service.DatabaseConversationStore;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import org.springframework.test.util.ReflectionTestUtils;

class ChatMemoryConfigReasoningRoundTripTest {

    @Test
    void reconstructsPersistedToolAndFinalReasoningAfterMemoryCacheIsRecreated() {
        ToolExecutionRequest call = ToolExecutionRequest.builder()
                .id("call-1").name("read_file").arguments("{\"path\":\"notes.txt\"}").build();
        List<ChatMessage> persistedTurn = List.of(
                UserMessage.from("Read notes.txt"),
                AiMessage.builder().text("").thinking("tool-call reasoning")
                        .toolExecutionRequests(List.of(call)).build(),
                ToolExecutionResultMessage.from("call-1", "read_file", "notes"),
                AiMessage.builder().text("The file says notes.").thinking("final reasoning").build());

        DatabaseConversationStore.AgentHistoryMessage user = new DatabaseConversationStore.AgentHistoryMessage();
        user.role = ChatRole.USER.getValue();
        user.content = "Read notes.txt";
        DatabaseConversationStore.AgentHistoryMessage assistant = new DatabaseConversationStore.AgentHistoryMessage();
        assistant.role = ChatRole.ASSISTANT.getValue();
        assistant.content = "The file says notes.";
        assistant.toolHistoryJson = AgentToolHistoryCodec.encodeLatestTurn(persistedTurn, true);

        DatabaseConversationStore store = mock(DatabaseConversationStore.class);
        when(store.agentHistory("session-restarted", 64)).thenReturn(List.of(user, assistant));
        ChatMemoryConfig config = new ChatMemoryConfig();
        ReflectionTestUtils.setField(config, "maxMessages", 64);
        ChatMemoryConfig.ChatMemoryProvider provider = config.chatMemoryProvider(store, null);

        ChatMemory rebuiltMemory = provider.get("session-restarted");
        List<ChatMessage> restored = rebuiltMemory.messages();

        assertThat(restored).hasSize(4);
        assertThat(restored.get(0)).isInstanceOf(UserMessage.class);
        AiMessage restoredToolCall = (AiMessage) restored.get(1);
        assertThat(restoredToolCall.thinking()).isEqualTo("tool-call reasoning");
        assertThat(restoredToolCall.toolExecutionRequests()).hasSize(1);
        assertThat(((ToolExecutionResultMessage) restored.get(2)).text()).isEqualTo("notes");
        AiMessage restoredFinal = (AiMessage) restored.get(3);
        assertThat(restoredFinal.text()).isEqualTo("The file says notes.");
        assertThat(restoredFinal.thinking()).isEqualTo("final reasoning");
    }
}
