package com.miniagent.migration;

import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CanaryAssignmentOutboxMigrationTest {
    @Test
    void createsOutboxAndUpgradesApplicationIndexIdempotently() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:canary-outbox-migration;MODE=MySQL;DB_CLOSE_DELAY=-1")) {
            createLegacyApplications(connection);
            Context context = mock(Context.class);
            when(context.getConnection()).thenReturn(connection);
            V20__CanaryAssignmentOutbox migration = new V20__CanaryAssignmentOutbox();

            migration.migrate(context);
            migration.migrate(context);

            assertEquals(Set.of("tenant_id", "user_id", "session_id", "task_id", "node_id",
                    "procedure_id", "procedure_version", "procedure_variant"),
                    indexColumns(connection, "agent_procedure_applications", "ux_apa_attempt_v20"));
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES "
                    + "WHERE TABLE_NAME='AGENT_CANARY_ASSIGNMENT_OUTBOX'"));
            try (Statement statement = connection.createStatement()) {
                statement.execute("INSERT INTO agent_procedure_applications "
                        + "(tenant_id,user_id,session_id,task_id,node_id,procedure_id,procedure_version,action_id,result) "
                        + "VALUES ('t',1,'s',1,'n',1,1,'a','SUCCESS')");
                statement.execute("INSERT INTO agent_procedure_applications "
                        + "(tenant_id,user_id,session_id,task_id,node_id,procedure_id,procedure_version,procedure_variant,action_id,result) "
                        + "VALUES ('t',1,'s',1,'n',1,1,'CANDIDATE','b','SUCCESS')");
                assertThrows(Exception.class, () -> statement.execute("INSERT INTO agent_procedure_applications "
                        + "(tenant_id,user_id,session_id,task_id,node_id,procedure_id,procedure_version,procedure_variant,action_id,result) "
                        + "VALUES ('t',1,'s',1,'n',1,1,'BASELINE','c','FAILURE')"));
            }
        }
    }

    @Test
    void skipsApplicationIndexUpgradeWhenApplicationsTableDoesNotExist() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:canary-outbox-no-applications;MODE=MySQL;DB_CLOSE_DELAY=-1")) {
            Context context = mock(Context.class);
            when(context.getConnection()).thenReturn(connection);
            V20__CanaryAssignmentOutbox migration = new V20__CanaryAssignmentOutbox();
            migration.migrate(context);
            migration.migrate(context);
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES "
                    + "WHERE TABLE_NAME='AGENT_CANARY_ASSIGNMENT_OUTBOX'"));
        }
    }

    private static void createLegacyApplications(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE agent_procedure_applications ("
                    + "id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id VARCHAR(64) NOT NULL, user_id BIGINT NOT NULL,"
                    + "session_id VARCHAR(100) NOT NULL, task_id BIGINT NOT NULL, node_id VARCHAR(128) NOT NULL,"
                    + "procedure_id BIGINT NOT NULL, procedure_version INT NOT NULL, action_id VARCHAR(128) NOT NULL,"
                    + "result VARCHAR(16) NOT NULL)");
            statement.execute("CREATE UNIQUE INDEX ux_apa_attempt ON agent_procedure_applications "
                    + "(tenant_id,user_id,session_id,task_id,node_id,procedure_id,procedure_version)");
        }
    }

    private static Set<String> indexColumns(Connection connection, String table, String index) throws Exception {
        Set<String> columns = new HashSet<>();
        try (ResultSet rs = connection.getMetaData().getIndexInfo(
                connection.getCatalog(), null, table.toUpperCase(), false, false)) {
            while (rs.next()) if (index.equalsIgnoreCase(rs.getString("INDEX_NAME")))
                columns.add(rs.getString("COLUMN_NAME").toLowerCase());
        }
        return columns;
    }

    private static int count(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
