package com.miniagent.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Add per-user admin overrides and membership entitlements to the agent's quota authority. */
public class V38__PerUserMembershipTokenQuota extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        var connection = context.getConnection();
        try (var statement = connection.createStatement()) {
            if (!columnExists(connection, "users", "daily_token_limit_override")) {
                statement.execute("ALTER TABLE users ADD COLUMN daily_token_limit_override BIGINT NULL");
            }
            if (!columnExists(connection, "users", "membership_daily_token_limit")) {
                statement.execute("ALTER TABLE users ADD COLUMN membership_daily_token_limit BIGINT NULL");
            }
            if (!columnExists(connection, "users", "membership_max_concurrent_tasks")) {
                statement.execute("ALTER TABLE users ADD COLUMN membership_max_concurrent_tasks INT NULL");
            }
            if (!columnExists(connection, "users", "membership_expires_at")) {
                statement.execute("ALTER TABLE users ADD COLUMN membership_expires_at DATETIME(6) NULL");
            }
        }
    }

    private static boolean columnExists(java.sql.Connection connection, String table, String column)
            throws java.sql.SQLException {
        try (var columns = connection.getMetaData().getColumns(connection.getCatalog(), null, "%", "%")) {
            while (columns.next()) {
                if (table.equalsIgnoreCase(columns.getString("TABLE_NAME"))
                        && column.equalsIgnoreCase(columns.getString("COLUMN_NAME"))) return true;
            }
        }
        return false;
    }
}
