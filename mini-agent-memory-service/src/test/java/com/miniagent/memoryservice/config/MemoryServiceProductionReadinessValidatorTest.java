package com.miniagent.memoryservice.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MemoryServiceProductionReadinessValidatorTest {
    private static final String VALID_MEMORY_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    @Test
    void acceptsStrongProductionCredentials() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("DB_URL", "jdbc:mysql://db.example/mini_agent_memory?sslMode=VERIFY_IDENTITY")
                .withProperty("DB_USERNAME", "miniagent")
                .withProperty("spring.datasource.password", "D".repeat(32))
                .withProperty("memory.service.account-base-url", "https://account.example")
                .withProperty("memory.service.account-internal-key", "I".repeat(32))
                .withProperty("memory.service.memory-token-key", VALID_MEMORY_KEY);

        assertDoesNotThrow(() -> new MemoryServiceProductionReadinessValidator(environment).validate());
    }

    @Test
    void rejectsRepositoryCredentialsAndShortSecrets() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("DB_URL", "jdbc:mysql://db.example/mini_agent_memory?sslMode=VERIFY_IDENTITY")
                .withProperty("DB_USERNAME", "miniagent")
                .withProperty("spring.datasource.password", "short-db-password")
                .withProperty("memory.service.account-base-url", "https://account.example")
                .withProperty("memory.service.account-internal-key", "short")
                .withProperty("memory.service.memory-token-key", "c2hvcnQ=");

        assertThrows(IllegalStateException.class,
                () -> new MemoryServiceProductionReadinessValidator(environment).validate());
    }

    @Test
    void refusesTheDefaultRootDatabaseAccount() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("DB_URL", "jdbc:mysql://db.example/mini_agent_memory?sslMode=VERIFY_IDENTITY")
                .withProperty("DB_USERNAME", "root")
                .withProperty("spring.datasource.password", "D".repeat(32))
                .withProperty("memory.service.account-base-url", "https://account.example")
                .withProperty("memory.service.account-internal-key", "I".repeat(32))
                .withProperty("memory.service.memory-token-key", VALID_MEMORY_KEY);

        assertThrows(IllegalStateException.class,
                () -> new MemoryServiceProductionReadinessValidator(environment).validate());
    }

    @Test
    void refusesAutomaticEvolutionInProduction() {
        MockEnvironment environment = validEnvironment()
                .withProperty("memory.evolution.policy.default-mode", "AUTO");

        assertThrows(IllegalStateException.class,
                () -> new MemoryServiceProductionReadinessValidator(environment).validate());
    }

    @Test
    void acceptsEvolutionDisabledByDefault() {
        assertDoesNotThrow(() -> new MemoryServiceProductionReadinessValidator(validEnvironment()).validate());
    }

    private static MockEnvironment validEnvironment() {
        return new MockEnvironment()
                .withProperty("DB_URL", "jdbc:mysql://db.example/mini_agent_memory?sslMode=VERIFY_IDENTITY")
                .withProperty("DB_USERNAME", "miniagent")
                .withProperty("spring.datasource.password", "D".repeat(32))
                .withProperty("memory.service.account-base-url", "https://account.example")
                .withProperty("memory.service.account-internal-key", "I".repeat(32))
                .withProperty("memory.service.memory-token-key", VALID_MEMORY_KEY)
                .withProperty("memory.evolution.policy.default-mode", "OFF")
                .withProperty("memory.evolution.experiment.require-live-arms", "true");
    }
}
