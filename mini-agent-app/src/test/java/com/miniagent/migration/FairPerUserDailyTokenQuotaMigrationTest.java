package com.miniagent.migration;

import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FairPerUserDailyTokenQuotaMigrationTest {
    @Test
    void onlySystemTenantGetsPerUserLimitAndHistoricalTenantTotalsAreUntouched() throws Exception {
        try (var db = DriverManager.getConnection(
                "jdbc:h2:mem:user-quota-" + UUID.randomUUID() + ";MODE=MySQL");
             var sql = db.createStatement()) {
            sql.execute("CREATE TABLE tenants(id BIGINT PRIMARY KEY, slug VARCHAR(64), daily_token_limit BIGINT)");
            sql.execute("CREATE TABLE users(id BIGINT PRIMARY KEY)");
            sql.execute("CREATE TABLE tenant_daily_usage(id BIGINT PRIMARY KEY, tenant_id BIGINT, "
                    + "usage_date DATE, token_count BIGINT)");
            sql.execute("INSERT INTO tenants VALUES (1,'system',1000000),(2,'paid-org',500000)");
            sql.execute("INSERT INTO users VALUES (10)");
            sql.execute("INSERT INTO tenant_daily_usage VALUES (1,1,CURRENT_DATE,7321)");
            Context context = mock(Context.class);
            when(context.getConnection()).thenReturn(db);

            var migration = new V34__FairPerUserDailyTokenQuota();
            migration.migrate(context);
            migration.migrate(context);

            try (var rows = sql.executeQuery("SELECT daily_user_token_limit FROM tenants ORDER BY id")) {
                rows.next(); assertEquals(100_000L, rows.getLong(1));
                rows.next(); assertEquals(0L, rows.getLong(1));
            }
            try (var rows = sql.executeQuery("SELECT COUNT(*) FROM user_daily_usage")) {
                rows.next(); assertEquals(0L, rows.getLong(1), "legacy aggregate is never split across users");
            }
            try (var rows = sql.executeQuery("SELECT token_count FROM tenant_daily_usage WHERE id=1")) {
                rows.next(); assertEquals(7_321L, rows.getLong(1));
            }
        }
    }
}
