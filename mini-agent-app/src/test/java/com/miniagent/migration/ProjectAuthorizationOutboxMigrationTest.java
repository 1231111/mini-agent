package com.miniagent.migration;

import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProjectAuthorizationOutboxMigrationTest {
    @Test
    void addsVersionAndUniqueSnapshotOutboxIdempotently() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:project-auth-outbox;MODE=MySQL;DB_CLOSE_DELAY=-1")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE agent_projects (project_id VARCHAR(128) PRIMARY KEY)");
            }
            Context context = mock(Context.class);
            when(context.getConnection()).thenReturn(connection);
            V18__ProjectAuthorizationOutbox migration = new V18__ProjectAuthorizationOutbox();
            migration.migrate(context);
            assertDoesNotThrow(() -> migration.migrate(context));
            try (Statement statement = connection.createStatement()) {
                statement.execute("INSERT INTO agent_project_authorization_outbox "
                        + "(tenant_id,project_id,owner_user_id,member_user_ids_json,authorization_version,status,attempts,next_attempt_at) "
                        + "VALUES ('t','p',1,'[]',1,'PENDING',0,CURRENT_TIMESTAMP)");
                assertThrows(SQLException.class, () -> statement.execute("INSERT INTO agent_project_authorization_outbox "
                        + "(tenant_id,project_id,owner_user_id,member_user_ids_json,authorization_version,status,attempts,next_attempt_at) "
                        + "VALUES ('t','p',1,'[]',1,'PENDING',0,CURRENT_TIMESTAMP)"));
                statement.execute("INSERT INTO agent_project_authorization_outbox "
                        + "(tenant_id,project_id,owner_user_id,member_user_ids_json,authorization_version,status,attempts,next_attempt_at) "
                        + "VALUES ('t','p',1,'[]',2,'PENDING',0,CURRENT_TIMESTAMP)");
            }
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS "
                         + "WHERE TABLE_NAME='AGENT_PROJECTS' AND COLUMN_NAME='AUTHORIZATION_VERSION'")) {
                rs.next();
                assertEquals(1, rs.getInt(1));
            }
        }
    }
}
