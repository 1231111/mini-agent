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

    @Test
    void rejectsShortDatabaseAndRedisCredentials() {
        ProductionReadinessValidator validator = new ProductionReadinessValidator();
        ReflectionTestUtils.setField(validator, "environment", new MockEnvironment()
                .withProperty("spring.datasource.password", "D".repeat(32))
                .withProperty("spring.data.redis.password", "too-short"));
        List<String> errors = new ArrayList<>();

        ReflectionTestUtils.invokeMethod(validator, "requireProductionSecret",
                "spring.datasource.password", "DB_PASSWORD", 32, errors);
        ReflectionTestUtils.invokeMethod(validator, "requireProductionSecret",
                "spring.data.redis.password", "REDIS_PASSWORD", 32, errors);

        assertTrue(errors.stream().anyMatch(error -> error.contains("at least 32 bytes")), errors::toString);
    }

    @Test
    void rejectsShortJwtSecretAndRequiresExplicitBlockForProductionShell() {
        ProductionReadinessValidator validator = new ProductionReadinessValidator();
        ReflectionTestUtils.setField(validator, "environment", new MockEnvironment()
                .withProperty("agent.auth.jwt-secret", "short-jwt-secret"));
        List<String> errors = new ArrayList<>();
        ReflectionTestUtils.invokeMethod(validator, "validateJwtSecret", errors);

        assertTrue(errors.stream().anyMatch(error -> error.contains("at least 256 bits")), errors::toString);
        assertTrue(ProductionReadinessValidator.isProductionExecPolicy("block"));
        assertFalse(ProductionReadinessValidator.isProductionExecPolicy("ask"));
        assertFalse(ProductionReadinessValidator.isProductionExecPolicy("allow"));
    }

    @Test
    void productionEnvironmentPassthroughMustStayEmpty() {
        assertFalse(ProductionReadinessValidator.hasProductionEnvironmentPassthrough(null));
        assertFalse(ProductionReadinessValidator.hasProductionEnvironmentPassthrough("  "));
        assertTrue(ProductionReadinessValidator.hasProductionEnvironmentPassthrough("NPM_TOKEN"));
    }

    @Test
    void productionDatabaseUserMustNotBeRoot() {
        assertFalse(ProductionReadinessValidator.isProductionDatabaseUsername("root"));
        assertFalse(ProductionReadinessValidator.isProductionDatabaseUsername("  "));
        assertTrue(ProductionReadinessValidator.isProductionDatabaseUsername("miniagent"));
    }

    @Test
    void independentJudgeRequiresSeparateConfiguredCredentials() {
        ProductionReadinessValidator validator = new ProductionReadinessValidator();
        ReflectionTestUtils.setField(validator, "environment", new MockEnvironment()
                .withProperty("agent.planner.require-independent-judge", "true")
                .withProperty("AGENT_JUDGE_MODEL_NAME", "judge-model")
                .withProperty("AGENT_JUDGE_BASE_URL", "https://judge.example/v1")
                .withProperty("AGENT_JUDGE_API_KEY", "short"));
        List<String> errors = new ArrayList<>();

        ReflectionTestUtils.invokeMethod(validator, "validateIndependentJudge", errors);

        assertTrue(errors.stream().anyMatch(error -> error.contains("at least 16 bytes")), errors::toString);
    }

    @Test
    void independentJudgeRejectsMissingConfiguration() {
        ProductionReadinessValidator validator = new ProductionReadinessValidator();
        ReflectionTestUtils.setField(validator, "environment", new MockEnvironment()
                .withProperty("agent.planner.require-independent-judge", "true"));
        List<String> errors = new ArrayList<>();

        ReflectionTestUtils.invokeMethod(validator, "validateIndependentJudge", errors);

        assertTrue(errors.stream().anyMatch(error -> error.contains("judge-model-name")), errors::toString);
        assertTrue(errors.stream().anyMatch(error -> error.contains("judge-base-url")), errors::toString);
        assertTrue(errors.stream().anyMatch(error -> error.contains("judge-api-key")), errors::toString);
    }

    @Test
    void rejectsPlainHttpModelAndJudgeEndpoints() {
        ProductionReadinessValidator validator = new ProductionReadinessValidator();
        ReflectionTestUtils.setField(validator, "environment", new MockEnvironment()
                .withProperty("LLM_BASE_URL", "http://llm.example/v1")
                .withProperty("agent.planner.require-independent-judge", "true")
                .withProperty("AGENT_JUDGE_BASE_URL", "http://judge.example/v1"));
        List<String> errors = new ArrayList<>();

        ReflectionTestUtils.invokeMethod(validator, "validateLlmEndpoints", errors);
        ReflectionTestUtils.invokeMethod(validator, "validateIndependentJudge", errors);

        assertTrue(errors.stream().anyMatch(error -> error.contains("chat-model.base-url")
                && error.contains("HTTPS")), errors::toString);
        assertTrue(errors.stream().anyMatch(error -> error.contains("judge-base-url")
                && error.contains("HTTPS")), errors::toString);
    }
}
