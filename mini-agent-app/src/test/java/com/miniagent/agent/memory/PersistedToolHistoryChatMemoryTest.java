package com.miniagent.agent.memory;

import com.miniagent.config.service.DatabaseConversationStore;
import com.miniagent.config.storage.MediaStorage;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PersistedToolHistoryChatMemoryTest {

    @Test
    void restoresCompletedToolExchangeBeforeTheStoredAssistantAnswer() {
        List<ChatMessage> run = List.of(
                UserMessage.from("read the file"),
                AiMessage.from(ToolExecutionRequest.builder().id("read-1").name("read_file")
                        .arguments("{\"path\":\"a.txt\"}").build()),
                ToolExecutionResultMessage.from("read-1", "read_file", "file body"));
        DatabaseConversationStore.AgentHistoryMessage user = message("user", "read the file", null);
        DatabaseConversationStore.AgentHistoryMessage assistant = message("assistant", "Found the file.",
                com.miniagent.config.service.AgentToolHistoryCodec.encodeLatestTurn(run));
        DatabaseConversationStore store = mock(DatabaseConversationStore.class);
        when(store.agentHistory("session-1", 64)).thenReturn(List.of(user, assistant));

        ChatMemoryConfig config = new ChatMemoryConfig();
        ReflectionTestUtils.setField(config, "maxMessages", 64);
        ChatMemory memory = config.chatMemoryProvider(store, mock(MediaStorage.class)).get("session-1");

        List<ChatMessage> restored = memory.messages();
        assertEquals(4, restored.size());
        assertInstanceOf(UserMessage.class, restored.get(0));
        AiMessage call = assertInstanceOf(AiMessage.class, restored.get(1));
        assertEquals("read-1", call.toolExecutionRequests().getFirst().id());
        ToolExecutionResultMessage result = assertInstanceOf(ToolExecutionResultMessage.class, restored.get(2));
        assertEquals("file body", result.text());
        assertEquals("Found the file.", assertInstanceOf(AiMessage.class, restored.get(3)).text());
    }

    @Test
    void trimsOnlyWholeTurnsWhenTheConfiguredWindowIsTooSmallForToolHistory() {
        List<ChatMessage> turn = List.of(
                UserMessage.from("read the file"),
                AiMessage.from(ToolExecutionRequest.builder().id("read-2").name("read_file")
                        .arguments("{}").build()),
                ToolExecutionResultMessage.from("read-2", "read_file", "file body"),
                AiMessage.from("Found the file."));

        List<ChatMessage> compacted = ChatMemoryConfig.boundedWholeTurns(List.of(turn), 2);

        assertEquals(2, compacted.size());
        assertInstanceOf(UserMessage.class, compacted.getFirst());
        assertEquals("Found the file.", assertInstanceOf(AiMessage.class, compacted.getLast()).text());
    }

    private static DatabaseConversationStore.AgentHistoryMessage message(String role, String content,
                                                                          String toolHistoryJson) {
        DatabaseConversationStore.AgentHistoryMessage message = new DatabaseConversationStore.AgentHistoryMessage();
        message.role = role;
        message.content = content;
        message.toolHistoryJson = toolHistoryJson;
        return message;
    }
}
