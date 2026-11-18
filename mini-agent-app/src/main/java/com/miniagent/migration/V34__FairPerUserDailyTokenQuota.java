package com.miniagent.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Add a post-migration per-user budget only to the self-hosted shared tenant. */
public class V34__FairPerUserDailyTokenQuota extends BaseJavaMigration {
    private static final long SYSTEM_USER_DAILY_LIMIT = 100_000L;

    @Override
    public void migrate(Context context) throws Exception {
        var connection = context.getConnection();
        try (var statement = context.getConnection().createStatement()) {
            if (!columnExists(connection, "tenants", "daily_user_token_limit")) {
                statement.execute("ALTER TABLE tenants ADD COLUMN daily_user_token_limit BIGINT NOT NULL DEFAULT 0");
            }
            statement.execute("UPDATE tenants SET daily_user_token_limit=" + SYSTEM_USER_DAILY_LIMIT
                    + " WHERE slug='system' AND daily_user_token_limit<=0");
            statement.execute("CREATE TABLE IF NOT EXISTS user_daily_usage ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT,"
                    + "user_id BIGINT NOT NULL,"
                    + "usage_date DATE NOT NULL,"
                    + "token_count BIGINT NOT NULL DEFAULT 0,"
                    + "created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),"
                    + "updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),"
                    + "PRIMARY KEY (id),"
                    + "UNIQUE KEY uk_user_daily_usage (user_id, usage_date),"
                    + "KEY idx_user_daily_usage_date (usage_date),"
                    + "CONSTRAINT fk_user_daily_usage_user FOREIGN KEY (user_id) REFERENCES users (id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
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
