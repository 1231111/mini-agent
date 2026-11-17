package com.miniagent.config.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ProductionReadinessValidatorTest {

    @Test
    void acceptsMiMoCredentialFromExternalConfigWhenLlmEnvIsAbsent() {
        ProductionReadinessValidator validator = new ProductionReadinessValidator();
        ReflectionTestUtils.setField(validator, "environment",
                new MockEnvironment().withProperty("MIMO_API_KEY", "tp-test-token"));
        List<String> errors = new ArrayList<>();

        ReflectionTestUtils.invokeMethod(validator, "requireConfiguredLlmKey", errors);

        assertTrue(errors.isEmpty(), errors::toString);
    }

    @Test
    void prefersEffectiveChatModelProperty() {
        ProductionReadinessValidator validator = new ProductionReadinessValidator();
        ReflectionTestUtils.setField(validator, "environment",
                new MockEnvironment()
                        .withProperty("langchain4j.open-ai.chat-model.api-key", "tp-effective-token")
                        .withProperty("MIMO_API_KEY", ""));
        List<String> errors = new ArrayList<>();

        ReflectionTestUtils.invokeMethod(validator, "requireConfiguredLlmKey", errors);

        assertFalse(errors.stream().anyMatch(e -> e.contains("required")), errors::toString);
    }
}
