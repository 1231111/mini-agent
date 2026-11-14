package com.miniagent.config.service;

import com.miniagent.config.entity.UserModelConfig;
import com.miniagent.config.model.AgentModelsProperties;
import com.miniagent.config.repository.UserModelConfigRepository;
import com.miniagent.config.security.SecretCryptoService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class UserModelConfigWindowTest {
    private UserModelConfigService service;
    private UserModelConfigRepository repository;
    private final AtomicReference<UserModelConfig> stored = new AtomicReference<>();

    @BeforeEach void setup() {
        repository = mock(UserModelConfigRepository.class);
        when(repository.findByUserId(anyLong())).thenAnswer(call -> Optional.ofNullable(stored.get()));
        when(repository.save(any(UserModelConfig.class))).thenAnswer(call -> { stored.set(call.getArgument(0)); return stored.get(); });
        SecretCryptoService crypto = mock(SecretCryptoService.class);
        when(crypto.encrypt(anyString())).thenAnswer(call -> "enc:" + call.getArgument(0));
        when(crypto.decrypt(anyString())).thenAnswer(call -> ((String) call.getArgument(0)).substring(4));
        AgentModelsProperties properties = new AgentModelsProperties();
        AgentModelsProperties.Preset preset = new AgentModelsProperties.Preset();
        preset.setId("default"); preset.setBaseUrl("https://platform.example/v1"); preset.setModelName("platform");
        preset.setContextWindowTokens(512000); properties.setPresets(List.of(preset));
        service = new UserModelConfigService();
        ReflectionTestUtils.setField(service, "repository", repository);
        ReflectionTestUtils.setField(service, "crypto", crypto);
        ReflectionTestUtils.setField(service, "modelsProperties", properties);
        ReflectionTestUtils.setField(service, "customBaseUrlEnabled", true);
        ReflectionTestUtils.setField(service, "globalBaseUrl", "https://platform.example/v1");
        ReflectionTestUtils.setField(service, "globalModelName", "platform");
        ReflectionTestUtils.setField(service, "globalApiKey", "fixture-platform-key");
    }

    @Test void customEndpointOrModelNeverBorrowsTheOriginalPresetsWindow() {
        Map<String, Object> view = service.save(1L, "default", "https://own.example/v1", "small-model", "own-key", 0);
        assertEquals(true, view.get("success"));
        assertEquals(8192, service.getEffective(1L).contextWindowTokens());
        assertEquals("conservative", current(view).get("contextWindowSource"));
        assertTrue(current(view).get("contextWindowWarning").toString().contains("未确认"));
        assertEquals(0, current(view).get("customContextWindowTokens"));
        assertEquals(true, service.save(1L, "default", null, "another-model", "platform-own-key", 0).get("success"));
        assertEquals(8192, service.getEffective(1L).contextWindowTokens(), "model override alone is a new capacity");
    }

    @Test void explicitCustomWindowPersistsAndIsReturnedAsTheEffectiveInputBudget() {
        Map<String, Object> result = service.save(1L, "default", "https://own.example/v1", "small-model", "own-key", 32000);
        assertEquals(true, result.get("success"));
        assertEquals(32000, stored.get().getCustomContextWindowTokens());
        assertEquals(32000, service.getEffective(1L).contextWindowTokens());
        assertEquals(32000, current(result).get("contextWindowTokens"));
        assertEquals("user", current(result).get("contextWindowSource"));
        assertEquals("", current(result).get("contextWindowWarning"));
    }

    @Test void automaticPresetUsesItsOwnWindowAndUserLimitCannotRaiseKnownCapacity() {
        assertEquals(true, service.save(1L, "default", null, null, "", 0).get("success"));
        assertEquals(512000, service.getEffective(1L).contextWindowTokens());
        assertEquals(true, service.save(1L, "default", null, null, "", 1000000).get("success"));
        assertEquals(512000, service.getEffective(1L).contextWindowTokens());
        assertEquals(true, service.save(1L, "default", null, null, "", 32000).get("success"));
        assertEquals(32000, service.getEffective(1L).contextWindowTokens());
    }

    @Test void oldSaveSignaturePreservesCapacityOnlyWhenTheEffectiveModelAndEndpointStayTheSame() {
        service.save(1L, "default", "https://own.example/v1", "small-model", "own-key", 32000);
        assertEquals(true, service.save(1L, "default", "https://own.example/v1/", "small-model", "").get("success"));
        assertEquals(32000, service.getEffective(1L).contextWindowTokens());
        assertEquals(true, service.save(1L, "default", "https://own.example/v1", "new-model", "").get("success"));
        assertEquals(8192, service.getEffective(1L).contextWindowTokens());
        assertNull(stored.get().getCustomContextWindowTokens());
        service.save(1L, "default", "https://own.example/v1", "new-model", "", 64000);
        assertEquals(true, service.save(1L, "default", "https://different.example/v1", "new-model", "different-key").get("success"));
        assertEquals(8192, service.getEffective(1L).contextWindowTokens());
    }

    @Test void invalidWindowsRejectTheEntireUpdateBeforeMutatingManagedState() {
        service.save(1L, "default", "https://own.example/v1", "small-model", "own-key", 32000);
        clearInvocations(repository);
        for (int invalid : List.of(-1, 1, 1023, 10000001)) {
            Map<String, Object> result = service.save(1L, "default", "https://other.example/v1", "bad-model", "new-key", invalid);
            assertEquals(false, result.get("success")); assertEquals("INVALID_CONTEXT_WINDOW", result.get("errorCode"));
            assertEquals("small-model", stored.get().getCustomModelName());
            assertEquals(32000, stored.get().getCustomContextWindowTokens());
            assertEquals("https://own.example/v1", stored.get().getCustomApiKeyBaseUrl());
        }
        verify(repository, never()).save(any());
    }

    @Test void acceptsBothWindowBoundsAndAllowsExplicitResetToUnknown() {
        for (int window : List.of(1024, 10000000, 0)) {
            assertEquals(true, service.save(1L, "default", "https://own.example/v1", "small-model", "own-key", window).get("success"));
            assertEquals(window == 0 ? 8192 : window, service.getEffective(1L).contextWindowTokens());
        }
    }

    @Test void legacyOverrideWithoutWindowIsConservativeAndCorruptWindowRemainsRepairable() {
        service.save(1L, "default", "https://own.example/v1", "small-model", "own-key");
        assertNull(stored.get().getCustomContextWindowTokens());
        assertEquals(8192, service.getEffective(1L).contextWindowTokens());
        stored.get().setCustomContextWindowTokens(-1);
        assertThrows(IllegalStateException.class, () -> service.getEffective(1L));
        assertEquals(false, current(service.getView(1L)).get("configurationValid"));
        assertEquals(true, service.save(1L, "default", "https://own.example/v1", "small-model", "", 32000).get("success"));
    }

    @SuppressWarnings("unchecked") private static Map<String, Object> current(Map<String, Object> view) {
        return (Map<String, Object>) view.get("current");
    }
}
