package com.miniagent.migration;

import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskRunWaitingStatusMigrationTest {
    private static final String LEGACY = "'CANCELLED','COMPLETED','FAILED','INTERRUPTED','RUNNING'";

    @Test
    void nativeH2EnumAcceptsWaitingAndRetainsDataDefaultsAndOtherConstraints() throws Exception {
        try (Connection connection = database(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE agent_task_runs (id BIGINT PRIMARY KEY, user_id BIGINT CHECK(user_id > 0),"
                    + "status ENUM(" + LEGACY + ") DEFAULT 'RUNNING' NOT NULL)");
            statement.execute("CREATE TABLE unrelated_runs (status ENUM(" + LEGACY + "))");
            statement.execute("INSERT INTO agent_task_runs(id,user_id,status) VALUES(1,1,'COMPLETED')");
            assertThrows(SQLException.class, () -> statement.execute(
                    "INSERT INTO agent_task_runs(id,user_id,status) VALUES(2,1,'WAITING')"));

            migrateTwice(connection);

            statement.execute("INSERT INTO agent_task_runs(id,user_id,status) VALUES(2,1,'WAITING')");
            statement.execute("INSERT INTO agent_task_runs(id,user_id) VALUES(3,1)");
            assertEquals("COMPLETED", value(connection, "SELECT status FROM agent_task_runs WHERE id=1"));
            assertEquals("RUNNING", value(connection, "SELECT status FROM agent_task_runs WHERE id=3"));
            assertThrows(SQLException.class, () -> statement.execute(
                    "INSERT INTO agent_task_runs(id,user_id,status) VALUES(4,1,'INVALID')"));
            assertThrows(SQLException.class, () -> statement.execute(
                    "INSERT INTO agent_task_runs(id,user_id,status) VALUES(4,1,NULL)"));
            assertThrows(SQLException.class, () -> statement.execute(
                    "INSERT INTO agent_task_runs(id,user_id,status) VALUES(4,0,'WAITING')"));
            assertThrows(SQLException.class, () -> statement.execute("INSERT INTO unrelated_runs VALUES('WAITING')"));
            assertEquals("ENUM", value(connection, "SELECT DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS "
                    + "WHERE TABLE_NAME='AGENT_TASK_RUNS' AND COLUMN_NAME='STATUS'"));
        }
    }

    @Test
    void oldVarcharStatusCheckIsWidenedWhileUnrelatedChecksAndTablesRemainEnforced() throws Exception {
        try (Connection connection = database(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE \"agent_task_runs\" (id BIGINT PRIMARY KEY, user_id BIGINT,"
                    + "\"status\" VARCHAR(20) NOT NULL,"
                    + "CONSTRAINT old_status_enum CHECK(\"status\" IN(" + LEGACY + ")),"
                    + "CONSTRAINT positive_user CHECK(user_id > 0),"
                    + "CONSTRAINT waiting_requires_owner CHECK(\"status\" <> 'WAITING' OR user_id=7))");
            statement.execute("CREATE TABLE unrelated_runs (status VARCHAR(20) "
                    + "CONSTRAINT unrelated_enum CHECK(status IN(" + LEGACY + ")))");
            assertThrows(SQLException.class, () -> statement.execute(
                    "INSERT INTO \"agent_task_runs\" VALUES(1,7,'WAITING')"));

            migrateTwice(connection);

            statement.execute("INSERT INTO \"agent_task_runs\" VALUES(1,7,'WAITING')");
            assertThrows(SQLException.class, () -> statement.execute(
                    "INSERT INTO \"agent_task_runs\" VALUES(2,7,'INVALID')"));
            assertThrows(SQLException.class, () -> statement.execute(
                    "INSERT INTO \"agent_task_runs\" VALUES(2,0,'RUNNING')"));
            assertThrows(SQLException.class, () -> statement.execute(
                    "INSERT INTO \"agent_task_runs\" VALUES(2,8,'WAITING')"));
            assertThrows(SQLException.class, () -> statement.execute("INSERT INTO unrelated_runs VALUES('WAITING')"));
            assertEquals("3", value(connection, "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS "
                    + "WHERE TABLE_NAME='agent_task_runs' AND CONSTRAINT_TYPE='CHECK'"));
        }
    }

    @Test
    void emptyCurrentSchemaIsSkippedWithoutChangingAnIdenticallyNamedTableInAnotherSchema() throws Exception {
        try (Connection connection = database(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA other");
            statement.execute("CREATE TABLE other.agent_task_runs (status ENUM(" + LEGACY + "))");
            migrateTwice(connection);
            assertThrows(SQLException.class, () -> statement.execute(
                    "INSERT INTO other.agent_task_runs VALUES('WAITING')"));
            assertEquals("0", value(connection, "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES "
                    + "WHERE TABLE_SCHEMA='PUBLIC' AND TABLE_NAME='AGENT_TASK_RUNS'"));
        }
    }

    @Test
    void unrestrictedVarcharBaselineAndUnknownCustomStatusCheckAreNotChanged() throws Exception {
        try (Connection connection = database(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE agent_task_runs (status VARCHAR(20))");
            migrateTwice(connection);
            statement.execute("INSERT INTO agent_task_runs VALUES('WAITING'),('CUSTOM')");
            assertEquals("0", value(connection, "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS "
                    + "WHERE TABLE_NAME='AGENT_TASK_RUNS' AND CONSTRAINT_TYPE='CHECK'"));

            statement.execute("DROP TABLE agent_task_runs");
            statement.execute("CREATE TABLE agent_task_runs (status VARCHAR(20) CHECK(status IN('RUNNING','CUSTOM')))");
            migrateTwice(connection);
            statement.execute("INSERT INTO agent_task_runs VALUES('CUSTOM')");
            assertThrows(SQLException.class, () -> statement.execute("INSERT INTO agent_task_runs VALUES('WAITING')"));
        }
    }

    @Test
    void mysqlIsSkippedWithoutInspectingOrAlteringItsSchema() throws Exception {
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn("MySQL");
        migrateTwice(connection);
        verify(connection, never()).getSchema();
        verify(connection, never()).createStatement();
    }

    private static Connection database() throws SQLException {
        return DriverManager.getConnection("jdbc:h2:mem:waiting_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
    }

    private static void migrateTwice(Connection connection) throws Exception {
        Context context = mock(Context.class);
        when(context.getConnection()).thenReturn(connection);
        var migration = new V24__TaskRunWaitingStatus();
        assertEquals("24", migration.getVersion().getVersion());
        migration.migrate(context);
        migration.migrate(context);
    }

    private static String value(Connection connection, String query) throws SQLException {
        try (Statement statement = connection.createStatement(); var rows = statement.executeQuery(query)) {
            rows.next();
            return rows.getString(1);
        }
    }
}
