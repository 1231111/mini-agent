package com.miniagent.config.model;

import com.miniagent.config.service.UserModelConfigService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ModelClientFactoryCacheTest {
    @Test
    void reusedConnectionsReturnTheCurrentUsersWindowAndPreset() {
        UserModelConfigService service = mock(UserModelConfigService.class);
        EffectiveModelSettings large = settings("large", 128_000);
        EffectiveModelSettings small = settings("small", 32_000);
        when(service.getEffective(1L)).thenReturn(large);
        when(service.getEffective(2L)).thenReturn(small);
        ModelClientFactory factory = factory(service);

        var first = factory.resolve(1L);
        var second = factory.resolve(2L);

        assertSame(first.chat(), second.chat());
        assertSame(first.streaming(), second.streaming());
        assertEquals(small, second.settings());
        assertEquals(large, first.settings(), "Resolving another user must not mutate an active run");
    }

    @Test
    void updatedWindowIsAppliedOnTheNextResolveWithoutRebuildingClients() {
        UserModelConfigService service = mock(UserModelConfigService.class);
        when(service.getEffective(1L)).thenReturn(settings("same", 128_000), settings("same", 16_000));
        ModelClientFactory factory = factory(service);

        var old = factory.resolve(1L);
        var updated = factory.resolve(1L);

        assertSame(old.chat(), updated.chat());
        assertEquals(16_000, updated.settings().contextWindowTokens());
        assertEquals(128_000, old.settings().contextWindowTokens());
    }

    @Test
    void visionAndTextClientsKeepSeparateThinkingOptionsAndFreshSettings() {
        UserModelConfigService service = mock(UserModelConfigService.class);
        when(service.getEffective(1L)).thenReturn(settings("text", 128_000));
        when(service.getEffectivePreset("vision")).thenReturn(settings("vision", 32_000), settings("vision", 16_000));
        ModelClientFactory factory = factory(service);
        ReflectionTestUtils.setField(factory, "visionPreset", "vision");

        var text = factory.resolve(1L);
        var vision = factory.resolveForMultimodal(1L);
        var updated = factory.resolveForMultimodal(1L);

        assertNotSame(text.chat(), vision.chat());
        assertSame(vision.chat(), updated.chat());
        assertEquals(16_000, updated.settings().contextWindowTokens());
    }

    private static EffectiveModelSettings settings(String preset, int window) {
        return new EffectiveModelSettings(preset, preset, "http://127.0.0.1:9/v1", "fixture-model", "fixture-only", window);
    }

    private static ModelClientFactory factory(UserModelConfigService service) {
        ModelClientFactory factory = new ModelClientFactory();
        ReflectionTestUtils.setField(factory, "userModelConfigService", service);
        ReflectionTestUtils.setField(factory, "timeout", Duration.ofSeconds(1));
        return factory;
    }
}
