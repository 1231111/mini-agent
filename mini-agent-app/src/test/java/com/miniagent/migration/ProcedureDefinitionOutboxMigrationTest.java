package com.miniagent.migration;

import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProcedureDefinitionOutboxMigrationTest {

    @Test
    void createsOutboxWithVersionUniqueKeyAndIsRerunnable() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:procedure-definition-outbox;MODE=MySQL;DB_CLOSE_DELAY=-1")) {

            Context context = mock(Context.class);
            when(context.getConnection()).thenReturn(connection);
            V17__ProcedureDefinitionOutbox migration = new V17__ProcedureDefinitionOutbox();

            migration.migrate(context);
            // 重复执行必须安全：桌面档重装/重跑迁移链时会再次进入同一版本。
            assertDoesNotThrow(() -> migration.migrate(context));

            try (Statement statement = connection.createStatement()) {
                statement.execute(insert("1"));
                // 同一 (tenant, procedure, version) 只能有一条待投递记录，
                // 否则同一版本会被反复投递，队列也没法判断「这一版是否已送达」。
                assertThrows(SQLException.class, () -> statement.execute(insert("2")));
                // 换一个版本号则允许，这正是升版后追加一条的路径。
                assertDoesNotThrow(() -> statement.execute(insert("3").replace("1, 1,", "1, 2,")));
            }

            try (Statement statement = connection.createStatement();
                 ResultSet columns = statement.executeQuery("""
                         SELECT COUNT(*) AS c
                           FROM INFORMATION_SCHEMA.COLUMNS
                          WHERE TABLE_NAME = 'AGENT_PROCEDURE_DEFINITION_OUTBOX'
                            AND COLUMN_NAME IN ('PROCEDURE_VERSION', 'SCOPE_TYPE', 'SCOPE_ID',
                                                'PROCEDURE_STATUS', 'NEXT_ATTEMPT_AT')
                         """)) {
                columns.next();
                assertEquals(5, columns.getInt("c"));
            }
        }
    }

    private static String insert(String suffix) {
        return "INSERT INTO agent_procedure_definition_outbox "
                + "(tenant_id, user_id, project_id, procedure_id, procedure_version, scope_type, scope_id, "
                + "name, steps_json, procedure_status, status, attempts, next_attempt_at, last_error) "
                + "VALUES ('tenant-a', 7, NULL, 1, 1, 'USER', '7', 'p-" + suffix + "', '[]', 'ACTIVE', "
                + "'PENDING', 0, CURRENT_TIMESTAMP, NULL)";
    }
}
