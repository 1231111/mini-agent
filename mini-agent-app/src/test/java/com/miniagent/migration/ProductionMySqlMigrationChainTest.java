package com.miniagent.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs the complete production migration chain against the disposable CI MySQL schema. */
class ProductionMySqlMigrationChainTest {
    private static final int LATEST_MIGRATION = 37;

    @Test
    void productionMigrationChainAppliesAndValidatesOnMySql() throws Exception {
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("MINIAGENT_SCHEMA_TEST")),
                "Set MINIAGENT_SCHEMA_TEST=true to run the destructive integration check on a disposable schema");
        String url = requiredEnvironment("SCHEMA_DB_URL");
        String username = requiredEnvironment("SCHEMA_DB_USERNAME");
        String password = requiredEnvironment("SCHEMA_DB_PASSWORD");

        try (Connection connection = DriverManager.getConnection(url, username, password)) {
            String productName = connection.getMetaData().getDatabaseProductName();
            String catalog = connection.getCatalog();
            assertTrue("MySQL".equalsIgnoreCase(productName),
                    "The production migration integration test requires MySQL, got " + productName);
            assertTrue(catalog != null && catalog.toLowerCase().contains("test"),
                    "Refusing to mutate a schema whose database name does not identify it as a test database");
            assertTrue(connection.getMetaData().getDatabaseMajorVersion() >= 8,
                    "Production migration test expects MySQL 8 or newer");
        }
        prepareMemoryServiceSchema(username);

        Flyway flyway = Flyway.configure()
                .dataSource(url, username, password)
                .locations("classpath:db/migration", "classpath:com/miniagent/migration")
                .baselineOnMigrate(false)
                .outOfOrder(false)
                .cleanDisabled(true)
                .load();

        flyway.migrate();
        flyway.validate();

        MigrationInfo[] applied = flyway.info().applied();
        Set<String> appliedVersions = java.util.Arrays.stream(applied)
                .map(MigrationInfo::getVersion)
                .filter(java.util.Objects::nonNull)
                .map(version -> version.getVersion())
                .collect(Collectors.toSet());
        Set<String> expectedVersions = IntStream.rangeClosed(1, LATEST_MIGRATION)
                .mapToObj(Integer::toString)
                .collect(Collectors.toSet());
        assertTrue(appliedVersions.containsAll(expectedVersions),
                "Every production SQL and Java migration must be recorded as applied; missing: "
                        + expectedDifference(expectedVersions, appliedVersions));
        assertEquals(0, flyway.info().pending().length, "No production migration may remain pending");

        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement()) {
            try (ResultSet rows = statement.executeQuery(
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE success = TRUE")) {
                rows.next();
                assertEquals(LATEST_MIGRATION, rows.getInt(1),
                        "The clean CI database must execute and record the full production chain");
            }

            // V24 deliberately changes only legacy desktop H2 enum/check schemas.
            // On MySQL, the production VARCHAR column must continue accepting WAITING.
            connection.setAutoCommit(false);
            try {
                statement.executeUpdate("INSERT INTO agent_task_runs(user_id, session_id, status) "
                        + "VALUES (1, 'migration-ci-contract', 'WAITING')");
                try (ResultSet rows = statement.executeQuery(
                        "SELECT status FROM agent_task_runs WHERE session_id = 'migration-ci-contract'")) {
                    assertTrue(rows.next());
                    assertEquals("WAITING", rows.getString(1));
                }
            } finally {
                connection.rollback();
            }
        }
    }

    private static void prepareMemoryServiceSchema(String username) throws Exception {
        String adminUrl = requiredEnvironment("SCHEMA_DB_ADMIN_URL");
        String adminUsername = requiredEnvironment("SCHEMA_DB_ADMIN_USERNAME");
        String adminPassword = requiredEnvironment("SCHEMA_DB_ADMIN_PASSWORD");
        String memoryUrl = requiredEnvironment("SCHEMA_MEMORY_DB_URL");
        String escapedUsername = username.replace("\\", "\\\\").replace("'", "''");

        try (Connection admin = DriverManager.getConnection(adminUrl, adminUsername, adminPassword);
             Statement statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE IF NOT EXISTS mini_agent_memory_schema_test "
                    + "CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
            statement.execute("GRANT ALL PRIVILEGES ON mini_agent_memory_schema_test.* TO '"
                    + escapedUsername + "'@'%'");
        }
        try (Connection memory = DriverManager.getConnection(memoryUrl, username,
                requiredEnvironment("SCHEMA_DB_PASSWORD"))) {
            String productName = memory.getMetaData().getDatabaseProductName();
            String catalog = memory.getCatalog();
            assertTrue("MySQL".equalsIgnoreCase(productName),
                    "The memory-service migration integration test requires MySQL, got " + productName);
            assertTrue("mini_agent_memory_schema_test".equalsIgnoreCase(catalog),
                    "Refusing to migrate a memory database other than the disposable test schema");
        }
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new AssertionError("MINIAGENT_SCHEMA_TEST=true requires " + name);
        }
        return value;
    }

    private static Set<String> expectedDifference(Set<String> expected, Set<String> actual) {
        return expected.stream().filter(version -> !actual.contains(version)).collect(Collectors.toSet());
    }
}
