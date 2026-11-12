package com.miniagent.config.model;

import com.miniagent.common.context.OwnerContext;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConfiguredToolVisionModelResolverTest {
    @AfterEach
    void clearOwner() { OwnerContext.clearCurrentUser(); }

    @Test
    void resolvesConfiguredVisionEndpointForTheAuthenticatedTaskOwner() {
        ModelClientFactory factory = mock(ModelClientFactory.class);
        ChatModel chat = mock(ChatModel.class);
        StreamingChatModel streaming = mock(StreamingChatModel.class);
        EffectiveModelSettings settings = new EffectiveModelSettings("vision", "Vision", "http://localhost", "model", "", 64000);
        when(factory.resolveForMultimodal(42L)).thenReturn(new ModelClientFactory.ResolvedModels(chat, streaming, settings));
        OwnerContext.setCurrentUser(42L);

        var result = new ConfiguredToolVisionModelResolver(factory).resolve();

        assertSame(chat, result.chat());
        assertSame(streaming, result.streaming());
        assertEquals(64000, result.contextWindowTokens());
        verify(factory).resolveForMultimodal(42L);
        verifyNoMoreInteractions(factory);
    }

    @Test
    void rejectsMissingOwnerWithoutResolvingAnyGlobalCredential() {
        OwnerContext.clearCurrentUser();
        ModelClientFactory factory = mock(ModelClientFactory.class);
        var resolver = new ConfiguredToolVisionModelResolver(factory);
        assertThrows(IllegalStateException.class, resolver::resolve);
        verifyNoInteractions(factory);
    }

    @Test
    void doesNotCacheAnotherOwnersResolvedModels() {
        ModelClientFactory factory = mock(ModelClientFactory.class);
        ChatModel first = mock(ChatModel.class), second = mock(ChatModel.class);
        EffectiveModelSettings settings = new EffectiveModelSettings("vision", "Vision", "http://localhost", "model", "", 32000);
        when(factory.resolveForMultimodal(1L)).thenReturn(new ModelClientFactory.ResolvedModels(first, null, settings));
        when(factory.resolveForMultimodal(2L)).thenReturn(new ModelClientFactory.ResolvedModels(second, null, settings));
        var resolver = new ConfiguredToolVisionModelResolver(factory);
        OwnerContext.setCurrentUser(1L);
        assertSame(first, resolver.resolve().chat());
        OwnerContext.setCurrentUser(2L);
        assertSame(second, resolver.resolve().chat());
    }
}
