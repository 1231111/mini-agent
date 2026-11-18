package com.miniagent.migration;

import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BoundLegacySystemTenantQuotaMigrationTest {
    @Test void onlyUnlimitedSystemTenantReceivesFiniteDefaultAndMigrationIsRepeatable() throws Exception {
        try (var db = DriverManager.getConnection(
                "jdbc:h2:mem:system-quota-" + UUID.randomUUID() + ";MODE=MySQL");
             var sql = db.createStatement()) {
            sql.execute("CREATE TABLE tenants(id BIGINT PRIMARY KEY, slug VARCHAR(64), daily_token_limit BIGINT)");
            sql.execute("INSERT INTO tenants VALUES (1,'system',0),(2,'system',700000),(3,'other',0)");
            Context context = mock(Context.class);
            when(context.getConnection()).thenReturn(db);

            var migration = new V32__BoundLegacySystemTenantQuota();
            migration.migrate(context);
            migration.migrate(context);

            try (var rows = sql.executeQuery("SELECT daily_token_limit FROM tenants ORDER BY id")) {
                rows.next(); assertEquals(1_000_000L, rows.getLong(1));
                rows.next(); assertEquals(700_000L, rows.getLong(1));
                rows.next(); assertEquals(0L, rows.getLong(1));
            }
        }
    }
}
