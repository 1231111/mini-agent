package com.miniagent.migration;

import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.miniagent.application.TaskProgressSnapshot;
import java.nio.file.Path;
import java.util.List;
import java.sql.DriverManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatTaskProgressMigrationTest {
    @Test
    void diskSnapshotSurvivesDatabaseCloseAndReopen(@TempDir Path directory) throws Exception {
        String url = "jdbc:h2:file:" + directory.resolve("conversation-progress").toAbsolutePath().toString().replace('\\', '/')
                + ";MODE=MySQL";
        var original = new TaskProgressSnapshot("exec-before-restart", 19L, "FAILURE", "TOOL_ERROR", List.of(
                new TaskProgressSnapshot.Item(1, "识别表单", "completed", "", List.of()),
                new TaskProgressSnapshot.Item(2, "校验字段", "blocked", "字段值不一致", List.of(1))));
        try (var connection = DriverManager.getConnection(url)) {
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE chat_tasks(id BIGINT PRIMARY KEY, answer LONGTEXT)");
            }
            Context context = mock(Context.class); when(context.getConnection()).thenReturn(connection);
            new V25__ChatTaskProgress().migrate(context);
            try (var insert = connection.prepareStatement("INSERT INTO chat_tasks(id,answer,task_progress_json) VALUES(?,?,?)")) {
                insert.setLong(1, 7L); insert.setString(2, "字段仍需修正"); insert.setString(3, original.toJson()); insert.executeUpdate();
            }
        }
        // Opening a new file connection starts H2 again, with no in-memory state.
        try (var connection = DriverManager.getConnection(url);
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT answer,task_progress_json FROM chat_tasks WHERE id=7")) {
            assertTrue(rows.next());
            assertEquals("字段仍需修正", rows.getString(1));
            assertEquals(original, TaskProgressSnapshot.fromJson(rows.getString(2)));
        }
    }

    @Test
    void freshDesktopDatabaseDoesNotFailBeforeHibernateCreatesChatTasks() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:progress-empty;MODE=MySQL")) {
            Context context = mock(Context.class); when(context.getConnection()).thenReturn(connection);
            assertDoesNotThrow(() -> new V25__ChatTaskProgress().migrate(context));
        }
    }

    @Test
    void upgradeIsIdempotentAndPreservesExistingMessages() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:progress-upgrade;MODE=MySQL")) {
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE chat_tasks(id BIGINT PRIMARY KEY, answer LONGTEXT)");
                statement.execute("INSERT INTO chat_tasks VALUES(1, '原有回复')");
            }
            Context context = mock(Context.class); when(context.getConnection()).thenReturn(connection);
            var migration = new V25__ChatTaskProgress(); migration.migrate(context); migration.migrate(context);
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT answer,task_progress_json FROM chat_tasks")) {
                assertTrue(rows.next()); assertEquals("原有回复", rows.getString(1)); assertNull(rows.getString(2));
            }
        }
    }
}
