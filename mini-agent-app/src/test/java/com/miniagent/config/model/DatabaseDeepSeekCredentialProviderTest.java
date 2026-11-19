package com.miniagent.config.model;

import com.miniagent.common.context.OwnerContext;
import com.miniagent.config.service.UserModelConfigService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DatabaseDeepSeekCredentialProviderTest {

    @Test
    void resolvesCurrentUsersDbKeyOnlyForOfficialDeepSeekEndpoint() {
        UserModelConfigService configs = mock(UserModelConfigService.class);
        when(configs.getEffective(41L)).thenReturn(new EffectiveModelSettings(
                "deepseek", "DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat", "db-user-key"));
        var provider = new DatabaseDeepSeekCredentialProvider(configs);

        try (var ignored = OwnerContext.bind(new OwnerContext(41L, "tenant-1"))) {
            var credential = provider.currentUserCredential().orElseThrow();
            assertEquals("db-user-key", credential.apiKey());
            assertEquals("https://api.deepseek.com", credential.baseUrl());
        }
        verify(configs).getEffective(41L);
    }

    @Test
    void neverForwardsAnotherProvidersDbKeyToDeepSeek() {
        UserModelConfigService configs = mock(UserModelConfigService.class);
        when(configs.getEffective(42L)).thenReturn(new EffectiveModelSettings(
                "custom", "Custom", "https://api.example.com/v1", "chat-model", "other-provider-key"));
        var provider = new DatabaseDeepSeekCredentialProvider(configs);

        try (var ignored = OwnerContext.bind(new OwnerContext(42L, "tenant-1"))) {
            assertTrue(provider.currentUserCredential().isEmpty());
        }
    }

    @Test
    void doesNotQueryDatabaseWithoutAnAuthenticatedOwner() {
        UserModelConfigService configs = mock(UserModelConfigService.class);
        var provider = new DatabaseDeepSeekCredentialProvider(configs);

        assertTrue(provider.currentUserCredential().isEmpty());
        verifyNoInteractions(configs);
    }
}
