package com.miniagent.agent.memory;

import com.miniagent.common.context.OwnerContext;
import com.miniagent.common.model.EffectiveModelContext;
import com.miniagent.config.model.ModelClientFactory;
import com.miniagent.config.model.UserModelBinder;
import com.miniagent.config.service.DatabaseConversationStore;
import com.miniagent.memory.MemoryService;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MemoryDailyAnalysisModelBindingTest {
    @AfterEach
    void clear() {
        EffectiveModelContext.clear();
        OwnerContext.clearCurrentUser();
        OwnerContext.clearCurrentTenant();
    }

    @Test
    void invalidUserConfigurationSkipsAnalysisWithoutSendingTranscriptToGlobalModel() {
        DatabaseConversationStore conversations = mock(DatabaseConversationStore.class);
        when(conversations.listUserIds()).thenReturn(List.of(7L));
        var conversation = new DatabaseConversationStore.Conversation();
        var message = new DatabaseConversationStore.Message();
        message.role = "user";
        message.content = "private user transcript";
        conversation.messages = List.of(message);
        when(conversations.activeConversationsSince(eq(7L), anyLong())).thenReturn(List.of(conversation));

        ModelClientFactory factory = mock(ModelClientFactory.class);
        when(factory.resolve(7L)).thenThrow(new IllegalStateException("请修复用户模型配置"));
        UserModelBinder binder = new UserModelBinder();
        ReflectionTestUtils.setField(binder, "modelClientFactory", factory);
        ChatModel global = mock(ChatModel.class);
        MemoryService memory = mock(MemoryService.class);
        MemoryDailyAnalysisService service = new MemoryDailyAnalysisService();
        ReflectionTestUtils.setField(service, "conversationStore", conversations);
        ReflectionTestUtils.setField(service, "userModelBinder", binder);
        ReflectionTestUtils.setField(service, "chatModel", global);
        ReflectionTestUtils.setField(service, "memoryService", memory);

        assertDoesNotThrow(service::dailyAnalysis);

        verify(factory).resolve(7L);
        verifyNoInteractions(global, memory);
        assertNull(OwnerContext.currentUserId());
        assertFalse(EffectiveModelContext.isBound());
    }
}
