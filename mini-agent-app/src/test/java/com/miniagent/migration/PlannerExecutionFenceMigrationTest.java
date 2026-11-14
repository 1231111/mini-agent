package com.miniagent.migration;

import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlannerExecutionFenceMigrationTest {
    @Test void existingSnapshotsGainExecutionFenceWithoutRewritingTheirJsonOrEvents() throws Exception {
        try (Connection connection = database()) {
            connection.createStatement().execute("CREATE TABLE agent_session_planner(session_id VARCHAR(100) PRIMARY KEY,state_json LONGTEXT,events_json LONGTEXT)");
            String json = "{\"executionId\":\"existing-execution\",\"version\":1,\"custom\":\"preserve\"}";
            try (var insert = connection.prepareStatement("INSERT INTO agent_session_planner VALUES('task',?,'[1,2]')")) {
                insert.setString(1, json); insert.executeUpdate();
            }
            migrate(connection); migrate(connection);
            try (var rows = connection.createStatement().executeQuery("SELECT * FROM agent_session_planner")) {
                assertTrue(rows.next()); assertEquals("existing-execution", rows.getString("execution_id"));
                assertEquals(json, rows.getString("state_json")); assertEquals("[1,2]", rows.getString("events_json"));
            }
        }
    }

    @Test void legacyMissingNullAndBlankIdentitiesRemainCompatibleAndCorruptRowsDoNotBlockOtherTasks() throws Exception {
        try (Connection connection = database()) {
            connection.createStatement().execute("CREATE TABLE agent_session_planner(session_id VARCHAR(100) PRIMARY KEY,state_json LONGTEXT)");
            String[] snapshots = {"{}", "{\"executionId\":null}", "{\"executionId\":\"\"}", "{broken", "{\"executionId\":42}"};
            try (var insert = connection.prepareStatement("INSERT INTO agent_session_planner VALUES(?,?)")) {
                for (int i = 0; i < snapshots.length; i++) {
                    insert.setString(1, "task-" + i); insert.setString(2, snapshots[i]); insert.executeUpdate();
                }
            }
            migrate(connection);
            try (var rows = connection.createStatement().executeQuery("SELECT session_id,execution_id FROM agent_session_planner ORDER BY session_id")) {
                for (int i = 0; i < snapshots.length; i++) {
                    assertTrue(rows.next());
                    if (i == 2) assertEquals("", rows.getString(2)); else assertNull(rows.getString(2));
                }
            }
        }
    }

    @Test void freshDesktopAndOtherSchemasAreLeftForTheirOwnSchemaOwner() throws Exception {
        try (Connection connection = database()) {
            connection.createStatement().execute("CREATE SCHEMA archive");
            connection.createStatement().execute("CREATE TABLE archive.agent_session_planner(session_id VARCHAR(100),state_json LONGTEXT)");
            migrate(connection);
            try (var columns = connection.getMetaData().getColumns(connection.getCatalog(), "ARCHIVE", "AGENT_SESSION_PLANNER", "EXECUTION_ID")) {
                assertFalse(columns.next());
            }
            try (var tables = connection.getMetaData().getTables(connection.getCatalog(), "PUBLIC", "AGENT_SESSION_PLANNER", new String[]{"TABLE"})) {
                assertFalse(tables.next());
            }
        }
    }

    private static Connection database() throws Exception {
        return DriverManager.getConnection("jdbc:h2:mem:planner-migration-" + UUID.randomUUID() + ";MODE=MySQL");
    }
    private static void migrate(Connection connection) throws Exception {
        Context context = mock(Context.class); when(context.getConnection()).thenReturn(connection);
        new V29__PlannerExecutionFence().migrate(context);
    }
}
