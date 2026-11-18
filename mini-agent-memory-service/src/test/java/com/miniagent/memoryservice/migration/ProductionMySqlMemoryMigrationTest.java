package com.miniagent.memoryservice.migration;

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

/** Runs the full memory-service Flyway chain on its separate disposable MySQL schema. */
class ProductionMySqlMemoryMigrationTest {
    private static final int LATEST_MIGRATION = 13;
    private static final String MEMORY_DATABASE = "mini_agent_memory_schema_test";

    @Test
    void memoryServiceMigrationChainAppliesAndValidatesOnMySql() throws Exception {
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("MINIAGENT_SCHEMA_TEST")),
                "Set MINIAGENT_SCHEMA_TEST=true to run the disposable MySQL integration check");
        String url = requiredEnvironment("SCHEMA_MEMORY_DB_URL");
        String username = requiredEnvironment("SCHEMA_DB_USERNAME");
        String password = requiredEnvironment("SCHEMA_DB_PASSWORD");
        prepareSchema(url, username, password);

        try (Connection connection = DriverManager.getConnection(url, username, password)) {
            assertTrue("MySQL".equalsIgnoreCase(connection.getMetaData().getDatabaseProductName()),
                    "Memory-service migrations must be verified on MySQL");
            assertEquals(MEMORY_DATABASE, connection.getCatalog(),
                    "Refusing to migrate any database other than the disposable memory test schema");
            assertTrue(connection.getMetaData().getDatabaseMajorVersion() >= 8,
                    "Production migration test expects MySQL 8 or newer");
        }

        Flyway flyway = Flyway.configure()
                .dataSource(url, username, password)
                .locations("classpath:db/migration-memory")
                .baselineOnMigrate(false)
                .outOfOrder(false)
                .cleanDisabled(true)
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
        assertTrue(versions.containsAll(expected), "Missing memory-service migration versions: "
                + expected.stream().filter(version -> !versions.contains(version)).collect(Collectors.toSet()));
        assertEquals(0, flyway.info().pending().length, "No memory-service migration may remain pending");

        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement()) {
            try (ResultSet rows = statement.executeQuery(
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE success = TRUE")) {
                rows.next();
                assertEquals(LATEST_MIGRATION, rows.getInt(1));
            }
            try (ResultSet rows = statement.executeQuery(
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() "
                            + "AND table_name = 'mem_subject_erasure'")) {
                rows.next();
                assertEquals(1, rows.getInt(1), "V13 must create the late-write tombstone table");
            }
        }
    }

    private static void prepareSchema(String url, String username, String password) throws Exception {
        String adminUrl = requiredEnvironment("SCHEMA_DB_ADMIN_URL");
        String adminUsername = requiredEnvironment("SCHEMA_DB_ADMIN_USERNAME");
        String adminPassword = requiredEnvironment("SCHEMA_DB_ADMIN_PASSWORD");
        String escapedUsername = username.replace("\\", "\\\\").replace("'", "''");
        try (Connection admin = DriverManager.getConnection(adminUrl, adminUsername, adminPassword);
             Statement statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE IF NOT EXISTS " + MEMORY_DATABASE
                    + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
            statement.execute("GRANT ALL PRIVILEGES ON " + MEMORY_DATABASE + ".* TO '"
                    + escapedUsername + "'@'%'");
        }
        try (Connection connection = DriverManager.getConnection(url, username, password)) {
            assertEquals(MEMORY_DATABASE, connection.getCatalog(),
                    "Refusing to create or migrate any database other than the disposable memory test schema");
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
