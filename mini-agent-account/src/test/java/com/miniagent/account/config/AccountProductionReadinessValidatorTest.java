package com.miniagent.account.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AccountProductionReadinessValidatorTest {
    private static final String VALID_MEMORY_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    @Test
    void acceptsStrongProductionCredentials() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("DB_URL", "jdbc:mysql://db.example/mini_agent?sslMode=VERIFY_IDENTITY")
                .withProperty("DB_USERNAME", "miniagent")
                .withProperty("spring.datasource.password", "D".repeat(32))
                .withProperty("agent.account.internal-key", "I".repeat(32))
                .withProperty("agent.account.memory-token-key", VALID_MEMORY_KEY);

        assertDoesNotThrow(() -> new AccountProductionReadinessValidator(environment).validate());
    }

    @Test
    void rejectsRepositoryCredentialsAndShortSecrets() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("DB_URL", "jdbc:mysql://db.example/mini_agent?sslMode=VERIFY_IDENTITY")
                .withProperty("DB_USERNAME", "miniagent")
                .withProperty("spring.datasource.password", "short-db-password")
                .withProperty("agent.account.internal-key", "short")
                .withProperty("agent.account.memory-token-key", "c2hvcnQ=");

        assertThrows(IllegalStateException.class,
                () -> new AccountProductionReadinessValidator(environment).validate());
    }

    @Test
    void refusesTheDefaultRootDatabaseAccount() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("DB_URL", "jdbc:mysql://db.example/mini_agent?sslMode=VERIFY_IDENTITY")
                .withProperty("DB_USERNAME", "root")
                .withProperty("spring.datasource.password", "D".repeat(32))
                .withProperty("agent.account.internal-key", "I".repeat(32))
                .withProperty("agent.account.memory-token-key", VALID_MEMORY_KEY);

        assertThrows(IllegalStateException.class,
                () -> new AccountProductionReadinessValidator(environment).validate());
    }

}
