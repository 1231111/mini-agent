package com.miniagent.migration;

import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PersistAgentToolHistoryMigrationTest {

    @Test
    void addsStructuredToolHistoryColumnIdempotentlyAndPreservesMessages() throws Exception {
        try (var connection = DriverManager.getConnection(
                "jdbc:h2:mem:agent-tool-history-" + UUID.randomUUID() + ";MODE=MySQL");
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE chat_messages(id BIGINT PRIMARY KEY, content LONGTEXT, images TEXT)");
            statement.execute("INSERT INTO chat_messages VALUES (1, 'answer', NULL)");
            Context context = mock(Context.class);
            when(context.getConnection()).thenReturn(connection);

            V33__PersistAgentToolHistory migration = new V33__PersistAgentToolHistory();
            migration.migrate(context);
            migration.migrate(context);

            try (var rows = statement.executeQuery(
                    "SELECT content, tool_history_json FROM chat_messages WHERE id=1")) {
                assertTrue(rows.next());
                assertEquals("answer", rows.getString(1));
                assertNull(rows.getString(2));
            }
        }
    }
}
