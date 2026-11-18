package com.miniagent.migration;

import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MembershipUsageOutboxMigrationTest {
    @Test
    void createsIdempotentUsageOutboxWithUniqueEventAndRetryIndex() throws Exception {
        try (var db = DriverManager.getConnection(
                "jdbc:h2:mem:membership-usage-outbox-" + UUID.randomUUID() + ";MODE=MySQL")) {
            Context context = mock(Context.class);
            when(context.getConnection()).thenReturn(db);
            var migration = new V39__MembershipUsageOutbox();
            migration.migrate(context);
            migration.migrate(context);

            try (var columns = db.getMetaData().getColumns(db.getCatalog(), null,
                    "MEMBERSHIP_USAGE_OUTBOX", "%")) {
                java.util.Set<String> found = new java.util.HashSet<>();
                while (columns.next()) found.add(columns.getString("COLUMN_NAME").toLowerCase());
                assertTrue(found.containsAll(java.util.Set.of("event_id", "cloud_user_id", "input_tokens",
                        "output_tokens", "status", "attempts", "next_attempt_at", "claim_token")));
            }
            try (var indexes = db.getMetaData().getIndexInfo(db.getCatalog(), null,
                    "MEMBERSHIP_USAGE_OUTBOX", false, false)) {
                java.util.Map<String, java.util.Set<String>> columnsByIndex = new java.util.HashMap<>();
                java.util.Map<String, Boolean> uniqueByIndex = new java.util.HashMap<>();
                while (indexes.next()) {
                    String index = indexes.getString("INDEX_NAME").toLowerCase();
                    String column = indexes.getString("COLUMN_NAME");
                    columnsByIndex.computeIfAbsent(index, ignored -> new java.util.HashSet<>())
                            .add(column.toLowerCase());
                    uniqueByIndex.put(index, !indexes.getBoolean("NON_UNIQUE"));
                }
                assertTrue(columnsByIndex.entrySet().stream().anyMatch(e -> uniqueByIndex.get(e.getKey())
                                && e.getValue().contains("event_id")),
                        "event_id must have a unique index: " + columnsByIndex);
                assertTrue(columnsByIndex.values().stream().anyMatch(columns ->
                                columns.contains("status") && columns.contains("next_attempt_at")),
                        "outbox must have an index for due event polling: " + columnsByIndex);
            }
            try (var rows = db.createStatement().executeQuery(
                    "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME='MEMBERSHIP_USAGE_OUTBOX'")) {
                rows.next();
                assertEquals(1, rows.getInt(1));
            }
        }
    }
}
