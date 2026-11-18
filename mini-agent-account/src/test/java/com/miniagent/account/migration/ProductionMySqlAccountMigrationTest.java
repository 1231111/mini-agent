package com.miniagent.account.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises the account service's separate history table on the shared agent database. */
class ProductionMySqlAccountMigrationTest {
    private static final int LATEST_MIGRATION = 4;

    @Test
    void accountMigrationsApplyToTheSharedMySqlSchema() throws Exception {
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("MINIAGENT_SCHEMA_TEST")),
                "Set MINIAGENT_SCHEMA_TEST=true to run the disposable MySQL integration check");
        String url = requiredEnvironment("SCHEMA_DB_URL");
        String username = requiredEnvironment("SCHEMA_DB_USERNAME");
        String password = requiredEnvironment("SCHEMA_DB_PASSWORD");

        try (Connection connection = DriverManager.getConnection(url, username, password)) {
            assertTrue("MySQL".equalsIgnoreCase(connection.getMetaData().getDatabaseProductName()),
                    "Account migrations must be verified on MySQL");
            String catalog = connection.getCatalog();
            assertTrue(catalog != null && catalog.toLowerCase().contains("test"),
                    "Refusing to mutate a schema whose database name does not identify it as a test database");
        }

        // Order is deliberately independent from JUnit class discovery order:
        // account V1 assumes the agent-owned shared schema already exists.
        Flyway agentFlyway = Flyway.configure()
                .dataSource(url, username, password)
                .locations("classpath:db/migration", "classpath:com/miniagent/migration")
                .cleanDisabled(true)
                .outOfOrder(false)
                .load();
        agentFlyway.migrate();
        agentFlyway.validate();

        // The account service shares the main database but owns a separate Flyway ledger.
        // baseline-on-migrate mirrors account's production settings after the agent chain creates its schema.
        Flyway flyway = Flyway.configure()
                .dataSource(url, username, password)
                .locations("classpath:db/migration")
                .table("flyway_schema_history_account")
                .baselineOnMigrate(true)
                .baselineVersion("1")
                .cleanDisabled(true)
                .outOfOrder(false)
                .load();

        flyway.migrate();
        flyway.validate();

        Set<String> versions = Arrays.stream(flyway.info().applied())
                .map(MigrationInfo::getVersion)
                .filter(java.util.Objects::nonNull)
                .map(version -> version.getVersion())
                .collect(Collectors.toSet());
        Set<String> expected = IntStream.rangeClosed(1, LATEST_MIGRATION)
                .mapToObj(Integer::toString)
                .collect(Collectors.toSet());
        assertTrue(versions.containsAll(expected), "Missing account migration versions: "
                + expected.stream().filter(version -> !versions.contains(version)).collect(Collectors.toSet()));
        assertEquals(0, flyway.info().pending().length);

        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement()) {
            try (ResultSet rows = statement.executeQuery(
                    "SELECT COUNT(*) FROM flyway_schema_history_account WHERE success = TRUE")) {
                rows.next();
                assertEquals(LATEST_MIGRATION, rows.getInt(1));
            }
            try (ResultSet rows = statement.executeQuery(
                    "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() "
                            + "AND table_name = 'users' AND column_name = 'memory_refresh_token_hash'")) {
                rows.next();
                assertEquals(1, rows.getInt(1), "V4 must add the account refresh-token credential column");
            }
            try (ResultSet rows = statement.executeQuery(
                    "SELECT COUNT(*) FROM membership_plans WHERE code = 'free'")) {
                rows.next();
                assertEquals(1, rows.getInt(1), "The shared-schema migration must retain the default free plan");
            }
        }
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new AssertionError("MINIAGENT_SCHEMA_TEST=true requires " + name);
        }
        return value;
    }
}
