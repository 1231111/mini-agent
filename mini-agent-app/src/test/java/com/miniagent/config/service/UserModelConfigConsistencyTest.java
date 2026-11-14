package com.miniagent.config.service;

import com.miniagent.config.entity.UserModelConfig;
import com.miniagent.config.model.AgentModelsProperties;
import com.miniagent.config.model.EffectiveModelSettings;
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UserModelConfigConsistencyTest {
    private UserModelConfigService service;
    private UserModelConfigRepository repository;
    private final AtomicReference<UserModelConfig> stored = new AtomicReference<>();

    @BeforeEach void setup() {
        service = new UserModelConfigService();
        repository = mock(UserModelConfigRepository.class);
        when(repository.findByUserId(anyLong())).thenAnswer(call -> Optional.ofNullable(stored.get()));
        when(repository.save(any())).thenAnswer(call -> { stored.set(call.getArgument(0)); return stored.get(); });
        doAnswer(call -> { stored.set(null); return null; }).when(repository).delete(any());
        SecretCryptoService crypto = mock(SecretCryptoService.class);
        when(crypto.encrypt(anyString())).thenAnswer(call -> "enc:" + call.getArgument(0));
        when(crypto.decrypt(anyString())).thenAnswer(call -> ((String) call.getArgument(0)).replaceFirst("^enc:", ""));
        AgentModelsProperties properties = new AgentModelsProperties();
        AgentModelsProperties.Preset platform = preset("default", "https://platform.example/v1", null);
        AgentModelsProperties.Preset separate = preset("separate", "https://separate.example/v1", null);
        AgentModelsProperties.Preset ready = preset("ready", "https://ready.example/v1", "sk-ready-platform");
        properties.setPresets(List.of(platform, separate, ready));
        ReflectionTestUtils.setField(service, "repository", repository);
        ReflectionTestUtils.setField(service, "crypto", crypto);
        ReflectionTestUtils.setField(service, "modelsProperties", properties);
        ReflectionTestUtils.setField(service, "globalBaseUrl", "https://platform.example/v1");
        ReflectionTestUtils.setField(service, "globalModelName", "platform-model");
        ReflectionTestUtils.setField(service, "globalApiKey", "sk-global-platform");
        ReflectionTestUtils.setField(service, "customBaseUrlEnabled", true);
    }

    @Test void rejectsDisabledCustomUrlAtomicallyIncludingExistingManagedEntities() {
        assertTrue(success(service.save(1L, "default", null, "old-model", "sk-old")));
        UserModelConfig original = stored.get();
        clearInvocations(repository);
        ReflectionTestUtils.setField(service, "customBaseUrlEnabled", false);
        Map<String, Object> result = service.save(1L, "ready", "https://api.deepseek.com/v1", "deepseek-chat", "sk-deepseek");
        assertFalse(success(result));
        assertEquals("CUSTOM_BASE_URL_DISABLED", result.get("errorCode"));
        assertTrue(result.get("message").toString().contains("均未保存"));
        assertEquals("default", original.getPresetId());
        assertNull(original.getCustomBaseUrl());
        assertEquals("old-model", original.getCustomModelName());
        assertEquals("enc:sk-old", original.getCustomApiKey());
        verify(repository, never()).save(any());
        assertEquals("sk-old", service.getEffective(1L).apiKey());
    }

    @Test void desktopCustomEndpointRequiresOwnKeyAndReturnsTheEffectiveConnection() {
        Map<String, Object> missingModel = service.save(1L, "default", "http://127.0.0.1:11880/v1", "", "sk-local-test");
        assertEquals("CUSTOM_ENDPOINT_REQUIRES_MODEL", missingModel.get("errorCode"));
        assertNull(stored.get());
        Map<String, Object> rejected = service.save(1L, "default", "http://127.0.0.1:11880/v1", "local-model", "");
        assertEquals("CUSTOM_ENDPOINT_REQUIRES_KEY", rejected.get("errorCode"));
        assertNull(stored.get());
        Map<String, Object> saved = service.save(1L, "default", "http://127.0.0.1:11880/v1", "local-model", "sk-local-test");
        assertTrue(success(saved));
        assertEquals("http://127.0.0.1:11880/v1", current(saved).get("baseUrl"));
        assertEquals("local-model", current(saved).get("modelName"));
        assertEquals("user", current(saved).get("apiKeySource"));
        assertEquals("sk-local-test", service.getEffective(1L).apiKey());
        assertEquals("http://127.0.0.1:11880/v1", stored.get().getCustomApiKeyBaseUrl());
        assertFalse(saved.toString().contains("sk-global-platform"));
        assertFalse(saved.toString().contains("sk-local-test"));
    }

    @Test void changingEndpointRequiresNewCredentialsAndDoesNotMoveStoredKey() {
        service.save(1L, "default", "https://one.example/v1", "one", "sk-one");
        clearInvocations(repository);
        assertEquals("CREDENTIAL_ENDPOINT_MISMATCH", service.save(1L, "default", "https://two.example/v1", "two", "").get("errorCode"));
        assertEquals("https://one.example/v1", stored.get().getCustomBaseUrl());
        assertEquals("one", stored.get().getCustomModelName());
        verify(repository, never()).save(any());
        assertTrue(success(service.save(1L, "default", "https://two.example/v1", "two", "sk-two")));
        assertEquals("sk-two", service.getEffective(1L).apiKey());
        assertEquals("CREDENTIAL_ENDPOINT_MISMATCH", service.save(1L, "ready", null, null, "").get("errorCode"));
        assertTrue(success(service.save(1L, "ready", null, null, "__CLEAR__")));
        assertNull(stored.get().getCustomApiKeyBaseUrl());
        assertEquals("sk-ready-platform", service.getEffective(1L).apiKey());
    }

    @Test void unchangedEndpointKeepsOwnKeyAndEquivalentPlatformUrlCanUsePlatformKey() {
        assertTrue(success(service.save(1L, "default", "https://PLATFORM.example:443/v1/", null, "")));
        assertEquals("sk-global-platform", service.getEffective(1L).apiKey());
        assertTrue(success(service.save(1L, "default", "https://one.example/v1", "one", "sk-one")));
        assertTrue(success(service.save(1L, "default", "https://one.example/v1/", "one-new", "")));
        assertEquals("sk-one", service.getEffective(1L).apiKey());
        assertEquals("one-new", service.getEffective(1L).modelName());
    }

    @Test void presetsCannotSendGlobalCredentialsToAnotherDestination() {
        EffectiveModelSettings separate = service.getEffectivePreset("separate");
        assertEquals("", separate.apiKey());
        assertEquals("API_KEY_REQUIRED", service.save(1L, "separate", null, null, "").get("errorCode"));
        assertTrue(success(service.save(1L, "separate", null, null, "sk-separate-user")));
        assertEquals("sk-separate-user", service.getEffective(1L).apiKey());
    }

    @Test void legacyBlockedUrlCannotSilentlyMixItsModelAndKeyWithThePlatformEndpoint() {
        service.save(1L, "default", "https://one.example/v1", "one", "sk-one");
        ReflectionTestUtils.setField(service, "customBaseUrlEnabled", false);
        IllegalStateException blocked = assertThrows(IllegalStateException.class, () -> service.getEffective(1L));
        assertTrue(blocked.getMessage().contains("模型设置"));
        Map<String, Object> view = service.getView(1L);
        assertTrue(success(view)); // Settings remain openable for repair.
        assertEquals(false, current(view).get("configurationValid"));
        assertEquals("", current(view).get("baseUrl"));
        assertEquals("", current(view).get("modelName"));
        assertEquals(false, current(view).get("hasApiKey"));
        assertEquals("https://one.example/v1", current(view).get("customBaseUrl"));
        assertNotNull(view.get("configurationError"));
        assertTrue(success(service.save(1L, "default", null, null, "__CLEAR__")));
        assertEquals("sk-global-platform", service.getEffective(1L).apiKey());
    }

    @Test void unsafeOrMalformedUrlsFailBeforeWritingAnyConfiguration() {
        for (String url : List.of("file:///tmp/model", "https://user:secret@example.com/v1", "https://example.com/v1?key=secret",
                "https://example.com/v1#fragment", "not a url", "http://host:0/v1")) {
            Map<String, Object> result = service.save(1L, "default", url, "wrong-model", "sk-wrong");
            assertFalse(success(result), url);
            assertEquals("INVALID_BASE_URL", result.get("errorCode"));
        }
        assertNull(stored.get());
        verify(repository, never()).save(any());
    }

    @Test void unknownPresetNeverSilentlySavesCredentialsToTheDefaultProvider() {
        assertTrue(success(service.save(1L, "default", null, "original", "sk-original")));
        clearInvocations(repository);
        Map<String, Object> result = service.save(1L, "does-not-exist", null, "other-model", "sk-other");
        assertFalse(success(result));
        assertEquals("INVALID_PRESET", result.get("errorCode"));
        assertEquals("default", stored.get().getPresetId());
        assertEquals("original", stored.get().getCustomModelName());
        assertEquals("sk-original", service.getEffective(1L).apiKey());
        verify(repository, never()).save(any());
    }

    @Test void legacyCustomDestinationWithoutOwnKeyFailsClosedButViewRemainsRepairable() {
        UserModelConfig row = new UserModelConfig();
        row.setUserId(1L); row.setCustomBaseUrl("https://one.example/v1"); stored.set(row);
        assertThrows(IllegalStateException.class, () -> service.getEffective(1L));
        assertEquals(false, current(service.getView(1L)).get("configurationValid"));
        assertTrue(success(service.save(1L, "default", "https://one.example/v1", "one", "sk-one")));
        assertEquals("sk-one", service.getEffective(1L).apiKey());
    }

    @Test void changingTheConfiguredUrlOfTheSamePresetCannotRedirectAnExistingUserKey() {
        assertTrue(success(service.save(1L, "default", null, "own-model", "sk-own")));
        AgentModelsProperties properties = (AgentModelsProperties) ReflectionTestUtils.getField(service, "modelsProperties");
        properties.findPreset("default").setBaseUrl("https://changed-by-admin.example/v1");
        assertThrows(IllegalStateException.class, () -> service.getEffective(1L));
        assertEquals(false, current(service.getView(1L)).get("configurationValid"));
        clearInvocations(repository);
        assertEquals("CREDENTIAL_ENDPOINT_MISMATCH", service.save(1L, "default", null, "new-model", "").get("errorCode"));
        verify(repository, never()).save(any());
        assertEquals("own-model", stored.get().getCustomModelName());
        assertTrue(success(service.save(1L, "default", null, "new-model", "sk-new-destination")));
        assertEquals("https://changed-by-admin.example/v1", stored.get().getCustomApiKeyBaseUrl());
        assertEquals("sk-new-destination", service.getEffective(1L).apiKey());
    }

    @Test void unboundEncryptedKeysRequireExplicitReentryAndDoNotMutateTheOldRow() {
        service.save(1L, "default", null, "original", "sk-own");
        stored.get().setCustomApiKeyBaseUrl(null);
        assertThrows(IllegalStateException.class, () -> service.getEffective(1L));
        clearInvocations(repository);
        assertEquals("CREDENTIAL_ENDPOINT_MISMATCH", service.save(1L, "default", null, "different", "").get("errorCode"));
        verify(repository, never()).save(any());
        assertEquals("original", stored.get().getCustomModelName());
        assertEquals("enc:sk-own", stored.get().getCustomApiKey());
        assertTrue(success(service.save(1L, "default", null, "different", "sk-reentered")));
        assertEquals("sk-reentered", service.getEffective(1L).apiKey());
    }

    @Test void historicalUnknownPresetFailsClosedAndRemainsRepairable() {
        service.save(1L, "default", null, "own", "sk-own");
        stored.get().setPresetId("removed-preset");
        assertThrows(IllegalStateException.class, () -> service.getEffective(1L));
        assertEquals(false, current(service.getView(1L)).get("configurationValid"));
        assertTrue(success(service.save(1L, "default", null, "own", "sk-reentered")));
        assertEquals("sk-reentered", service.getEffective(1L).apiKey());
    }

    @Test void rejectsOverlongFieldsBeforeDatabaseWrite() {
        assertEquals("CONFIGURATION_TOO_LONG", service.save(1L, "default", "https://one.example/" + "x".repeat(512),
                "model", "sk-own").get("errorCode"));
        assertEquals("CONFIGURATION_TOO_LONG", service.save(1L, "default", null, "x".repeat(129), "sk-own").get("errorCode"));
        verify(repository, never()).save(any());
        assertNull(stored.get());
    }

    private static AgentModelsProperties.Preset preset(String id, String url, String key) {
        AgentModelsProperties.Preset p = new AgentModelsProperties.Preset();
        p.setId(id); p.setBaseUrl(url); p.setApiKey(key); return p;
    }
    private static boolean success(Map<String, Object> result) { return Boolean.TRUE.equals(result.get("success")); }
    @SuppressWarnings("unchecked") private static Map<String, Object> current(Map<String, Object> view) {
        return (Map<String, Object>) view.get("current");
    }
}
