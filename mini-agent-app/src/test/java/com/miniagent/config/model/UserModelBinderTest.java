package com.miniagent.config.model;

import com.miniagent.common.context.OwnerContext;
import com.miniagent.common.model.EffectiveModelContext;
import com.miniagent.config.service.DatabaseConversationStore;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserModelBinderTest {
    private UserModelBinder binder;
    private ModelClientFactory models;
    private DatabaseConversationStore conversations;

    @BeforeEach
    void setUp() {
        clear();
        binder = new UserModelBinder();
        models = mock(ModelClientFactory.class);
        conversations = mock(DatabaseConversationStore.class);
        ReflectionTestUtils.setField(binder, "modelClientFactory", models);
        ReflectionTestUtils.setField(binder, "conversationStore", conversations);
    }

    @AfterEach
    void clear() {
        EffectiveModelContext.clear();
        OwnerContext.clearCurrentUser();
        OwnerContext.clearCurrentTenant();
        OwnerContext.setCurrentProjectId(null);
    }

    @Test
    void invalidUserConfigurationStopsBeforeGlobalModelCanBeUsed() {
        IllegalStateException invalid = new IllegalStateException("请修复用户模型配置");
        when(models.resolve(7L)).thenThrow(invalid);
        ChatModel global = mock(ChatModel.class);

        assertSame(invalid, assertThrows(IllegalStateException.class, () -> {
            try (var ignored = binder.bindModel(7L)) {
                EffectiveModelContext.chatOr(global).chat("private user transcript");
            }
        }));

        verifyNoInteractions(global);
        assertFalse(EffectiveModelContext.isBound());
    }

    @Test
    void failedBindingRestoresThePreviousOwnerProjectAndModel() {
        OwnerContext.setCurrentUser(99L);
        OwnerContext.setCurrentTenant("previous-tenant");
        OwnerContext.setCurrentProjectId("previous-project");
        ChatModel previous = mock(ChatModel.class);
        EffectiveModelContext.set(previous, null, 32_000);
        when(models.resolve(7L)).thenThrow(new IllegalStateException("invalid"));

        assertThrows(IllegalStateException.class, () -> binder.bindUser(7L));

        assertEquals(99L, OwnerContext.currentUserId());
        assertEquals("previous-tenant", OwnerContext.currentTenantId());
        assertEquals("previous-project", OwnerContext.currentProjectId());
        assertSame(previous, EffectiveModelContext.currentChat());
        assertEquals(32_000, EffectiveModelContext.currentContextWindow());
    }

    @Test
    void sessionOwnerLookupFailureCannotTurnIntoGlobalFallback() {
        when(conversations.findUserIdBySession("session")).thenThrow(new IllegalStateException("storage unavailable"));
        assertThrows(IllegalStateException.class, () -> binder.bindForSession("session"));
        verifyNoInteractions(models);
    }

    @Test
    void knownSessionWithInvalidModelDoesNotReturnNoopBinding() {
        when(conversations.findUserIdBySession("session")).thenReturn(Optional.of(7L));
        when(models.resolve(7L)).thenThrow(new IllegalStateException("invalid"));
        assertThrows(IllegalStateException.class, () -> binder.bindForSession("session"));
        assertNull(OwnerContext.currentUserId());
    }

    @Test
    void successfulNestedBindingRestoresTheOuterContextsAndWindow() {
        ChatModel previous = mock(ChatModel.class);
        ChatModel user = mock(ChatModel.class);
        EffectiveModelContext.set(previous, null, 128_000);
        OwnerContext.setCurrentUser(99L);
        when(models.resolve(7L)).thenReturn(new ModelClientFactory.ResolvedModels(user, null,
                new EffectiveModelSettings("user", "user", "http://127.0.0.1:9/v1", "fixture", "fixture", 8_192)));

        try (var ignored = binder.bindUser(7L)) {
            assertSame(user, EffectiveModelContext.currentChat());
            assertEquals(7L, OwnerContext.currentUserId());
            assertEquals(8_192, EffectiveModelContext.currentContextWindow());
        }
        assertSame(previous, EffectiveModelContext.currentChat());
        assertEquals(128_000, EffectiveModelContext.currentContextWindow());
        assertEquals(99L, OwnerContext.currentUserId());
    }
}
