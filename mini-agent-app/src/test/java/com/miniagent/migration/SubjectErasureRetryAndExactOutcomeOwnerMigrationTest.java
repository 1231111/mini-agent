package com.miniagent.migration;

import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SubjectErasureRetryAndExactOutcomeOwnerMigrationTest {
    @Test
    void backfillsStructuredOwnerAndAddsRetryLeaseColumns() throws Exception {
        try (var db = DriverManager.getConnection(
                "jdbc:h2:mem:subject-retry-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1")) {
            db.createStatement().execute("CREATE TABLE memory_subject_erasure (id BIGINT PRIMARY KEY,"
                    + "status VARCHAR(24) NOT NULL)");
            db.createStatement().execute("CREATE TABLE agent_memory_outcome_outbox (id BIGINT PRIMARY KEY,"
                    + "tenant_id VARCHAR(64) NOT NULL,payload_json LONGTEXT NOT NULL)");
            db.createStatement().execute("INSERT INTO memory_subject_erasure(id,status) VALUES(1,'PENDING_REMOTE')");
            db.createStatement().execute("INSERT INTO agent_memory_outcome_outbox VALUES"
                    + "(1,'tenant-a','{\"userId\":12}'),(2,'tenant-a','{\"userId\":123}')");
            Context context = mock(Context.class);
            when(context.getConnection()).thenReturn(db);

            new V37__SubjectErasureRetryAndExactOutcomeOwner().migrate(context);
            new V37__SubjectErasureRetryAndExactOutcomeOwner().migrate(context);

            try (var rows = db.createStatement().executeQuery(
                    "SELECT id,user_id FROM agent_memory_outcome_outbox ORDER BY id")) {
                rows.next();
                assertEquals(12L, rows.getLong(2));
                rows.next();
                assertEquals(123L, rows.getLong(2));
            }
            try (var rows = db.createStatement().executeQuery(
                    "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='MEMORY_SUBJECT_ERASURE'"
                            + " AND COLUMN_NAME IN ('ATTEMPTS','LEASE_UNTIL','CLAIM_TOKEN','NEXT_ATTEMPT_AT')")) {
                rows.next();
                assertEquals(4, rows.getInt(1));
            }
        }
    }
}
