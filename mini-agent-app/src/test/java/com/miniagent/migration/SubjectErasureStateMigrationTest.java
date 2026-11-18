package com.miniagent.migration;

import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SubjectErasureStateMigrationTest {
    @Test
    void createsIdempotentOwnerTombstoneTable() throws Exception {
        try (var db = DriverManager.getConnection(
                "jdbc:h2:mem:subject-erasure-" + UUID.randomUUID() + ";MODE=MySQL")) {
            Context context = mock(Context.class);
            when(context.getConnection()).thenReturn(db);
            var migration = new V35__SubjectErasureState();
            migration.migrate(context);
            migration.migrate(context);
            try (var rows = db.createStatement().executeQuery(
                    "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME='MEMORY_SUBJECT_ERASURE'")) {
                rows.next();
                assertEquals(1, rows.getInt(1));
            }
        }
    }
}
