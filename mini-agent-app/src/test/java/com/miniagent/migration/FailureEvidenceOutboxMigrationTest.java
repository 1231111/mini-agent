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

class FailureEvidenceOutboxMigrationTest {
    @Test void createsDurableQueueWithSourceUniquenessAndLeaseColumns() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:failure-evidence-outbox;MODE=MySQL;DB_CLOSE_DELAY=-1")) {
            Context context = mock(Context.class); when(context.getConnection()).thenReturn(connection);
            V19__FailureEvidenceOutbox migration = new V19__FailureEvidenceOutbox();
            migration.migrate(context);
            assertDoesNotThrow(() -> migration.migrate(context));
            try (Statement statement = connection.createStatement()) {
                String insert = "INSERT INTO agent_failure_evidence_outbox "
                        + "(tenant_id,user_id,session_id,task_id,node_id,procedure_id,procedure_version,failure_code,"
                        + "task_summary,resolution,status,attempts,next_attempt_at) VALUES "
                        + "('t',1,'s',2,'n',3,4,'failure','summary','resolution','PENDING',0,CURRENT_TIMESTAMP)";
                statement.execute(insert);
                assertThrows(SQLException.class, () -> statement.execute(insert));
                statement.execute(insert.replace("'t',1,'s',2,'n',3,4", "'t',1,'s',2,'n',3,5"));
            }
            try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(
                    "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='AGENT_FAILURE_EVIDENCE_OUTBOX' "
                            + "AND COLUMN_NAME IN ('LEASE_UNTIL','CLAIM_TOKEN','LAST_ERROR','NEXT_ATTEMPT_AT')")) {
                rs.next(); assertEquals(4, rs.getInt(1));
            }
        }
    }
}
