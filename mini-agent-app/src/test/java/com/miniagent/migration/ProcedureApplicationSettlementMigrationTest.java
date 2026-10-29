package com.miniagent.migration;

import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProcedureApplicationSettlementMigrationTest {

    @Test
    void addsProcedureCountersAndIdempotentApplicationTable() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:procedure-settlement-migration;MODE=MySQL;DB_CLOSE_DELAY=-1")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TABLE agent_procedures (
                            id BIGINT AUTO_INCREMENT PRIMARY KEY,
                            tenant_id VARCHAR(64) NOT NULL,
                            scope_type VARCHAR(32) NOT NULL,
                            scope_id VARCHAR(128) NOT NULL,
                            name VARCHAR(256) NOT NULL,
                            steps_json LONGTEXT NOT NULL
                        )
                        """);
                statement.execute("""
                        INSERT INTO agent_procedures
                            (tenant_id, scope_type, scope_id, name, steps_json)
                        VALUES ('tenant-a', 'USER', '9', 'p', '[]')
                        """);
            }

            Context context = mock(Context.class);
            when(context.getConnection()).thenReturn(connection);
            V12__ProcedureApplicationSettlement migration =
                    new V12__ProcedureApplicationSettlement();

            migration.migrate(context);
            migration.migrate(context);

            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        INSERT INTO agent_procedure_applications
                            (tenant_id, user_id, session_id, task_id, node_id,
                             procedure_id, procedure_version, action_id, result)
                        VALUES ('tenant-a', 9, 's', 4, 'n', 1, 1, 'a', 'SUCCESS')
                        """);
                assertThrows(Exception.class, () -> statement.execute("""
                        INSERT INTO agent_procedure_applications
                            (tenant_id, user_id, session_id, task_id, node_id,
                             procedure_id, procedure_version, action_id, result)
                        VALUES ('tenant-a', 9, 's', 4, 'n', 1, 1, 'b', 'FAILURE')
                        """));
                assertThrows(Exception.class, () -> statement.execute("""
                        INSERT INTO agent_procedure_applications
                            (tenant_id, user_id, session_id, task_id, node_id,
                             procedure_id, procedure_version, action_id, result)
                        VALUES ('tenant-a', 9, 's', 5, 'n', 1, 1, 'c', 'OTHER')
                        """));
            }

            try (Statement statement = connection.createStatement();
                 ResultSet columns = statement.executeQuery("""
                         SELECT COUNT(*) AS c
                           FROM INFORMATION_SCHEMA.COLUMNS
                          WHERE TABLE_NAME = 'AGENT_PROCEDURES'
                            AND COLUMN_NAME IN ('PROCEDURE_VERSION', 'SUCCESS_COUNT', 'ATTEMPT_COUNT')
                         """)) {
                columns.next();
                assertEquals(3, columns.getInt("c"));
            }
        }
    }
}
