package com.miniagent.migration;

import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PersistTaskScopeStateMigrationTest {

    @Test
    void migratesLegacyTodoTableAndCanRunTwice() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:scope-migration;DB_CLOSE_DELAY=-1")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TABLE agent_session_todos (
                            session_id VARCHAR(100) PRIMARY KEY,
                            version BIGINT
                        )
                        """);
                statement.execute("""
                        INSERT INTO agent_session_todos (session_id, version)
                        VALUES ('s1', NULL)
                        """);
                statement.execute("""
                        CREATE TABLE agent_session_permissions (
                            session_id VARCHAR(100) PRIMARY KEY
                        )
                        """);
                statement.execute("""
                        INSERT INTO agent_session_permissions (session_id)
                        VALUES ('s1')
                        """);
            }
            Context context = mock(Context.class);
            when(context.getConnection()).thenReturn(connection);
            V10__PersistTaskScopeState migration =
                    new V10__PersistTaskScopeState();

            migration.migrate(context);
            migration.migrate(context);

            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("""
                         SELECT current_task_id,
                                paused_task_id,
                                scope_sequence,
                                version
                           FROM agent_session_todos
                          WHERE session_id = 's1'
                         """)) {
                rows.next();
                assertEquals(0L, rows.getLong("current_task_id"));
                assertNull(rows.getObject("paused_task_id"));
                assertEquals(0L, rows.getLong("scope_sequence"));
                assertEquals(0L, rows.getLong("version"));
            }
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("""
                         SELECT exec_policy_override
                           FROM agent_session_permissions
                          WHERE session_id = 's1'
                         """)) {
                rows.next();
                assertNull(rows.getObject("exec_policy_override"));
            }
        }
    }
}
