package com.miniagent.migration;

import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MemoryWriteGateDenialAuditMigrationTest {
    @Test
    void createsIdempotentDenialAuditTable() throws Exception {
        try (var db = DriverManager.getConnection(
                "jdbc:h2:mem:gate-audit-" + UUID.randomUUID() + ";MODE=MySQL")) {
            Context context = mock(Context.class);
            when(context.getConnection()).thenReturn(db);
            var migration = new V36__MemoryWriteGateDenialAudit();
            migration.migrate(context);
            migration.migrate(context);
            try (var rows = db.createStatement().executeQuery(
                    "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES "
                            + "WHERE TABLE_NAME='AGENT_MEMORY_WRITE_GATE_DENIALS'")) {
                rows.next();
                assertEquals(1, rows.getInt(1));
            }
        }
    }
}
