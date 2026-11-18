package com.miniagent.migration;

import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PerUserMembershipTokenQuotaMigrationTest {
    @Test
    void addsNullablePerUserQuotaFieldsIdempotentlyWithoutChangingUsageOrIdentity() throws Exception {
        try (var db = DriverManager.getConnection(
                "jdbc:h2:mem:per-user-membership-quota-" + UUID.randomUUID() + ";MODE=MySQL");
             var sql = db.createStatement()) {
            sql.execute("CREATE TABLE users(id BIGINT PRIMARY KEY, username VARCHAR(50))");
            sql.execute("INSERT INTO users VALUES (7, 'member')");
            Context context = mock(Context.class);
            when(context.getConnection()).thenReturn(db);

            var migration = new V38__PerUserMembershipTokenQuota();
            migration.migrate(context);
            migration.migrate(context);

            try (var columns = db.getMetaData().getColumns(db.getCatalog(), null, "USERS", "%")) {
                java.util.Set<String> found = new java.util.HashSet<>();
                while (columns.next()) found.add(columns.getString("COLUMN_NAME").toLowerCase());
                assertTrue(found.contains("daily_token_limit_override"));
                assertTrue(found.contains("membership_daily_token_limit"));
                assertTrue(found.contains("membership_max_concurrent_tasks"));
                assertTrue(found.contains("membership_expires_at"));
            }
            try (var row = sql.executeQuery("SELECT username, daily_token_limit_override, "
                    + "membership_daily_token_limit, membership_max_concurrent_tasks FROM users WHERE id=7")) {
                row.next();
                assertEquals("member", row.getString(1));
                assertNull(row.getObject(2));
                assertNull(row.getObject(3));
                assertNull(row.getObject(4));
            }
        }
    }
}
