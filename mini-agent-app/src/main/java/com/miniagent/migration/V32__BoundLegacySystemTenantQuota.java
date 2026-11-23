package com.miniagent.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Give the shared local tenant a finite baseline budget instead of unlimited usage. */
public class V32__BoundLegacySystemTenantQuota extends BaseJavaMigration {
    private static final long DEFAULT_DAILY_TOKEN_LIMIT = 1_000_000L;

    @Override
    public void migrate(Context context) throws Exception {
        // On a brand-new desktop install Hibernate owns the baseline schema and
        // runs after Flyway. This data migration therefore precedes creation of
        // `tenants`; there is no legacy system row to update yet. Once the table
        // exists, preserve the same bounded quota backfill for upgraded installs.
        if (!tableExists(context, "tenants") || !columnExists(context, "tenants", "daily_token_limit")
                || !columnExists(context, "tenants", "slug")) {
            return;
        }
        try (var statement = context.getConnection().prepareStatement(
                "UPDATE tenants SET daily_token_limit=? WHERE slug=? AND daily_token_limit<=0")) {
            statement.setLong(1, DEFAULT_DAILY_TOKEN_LIMIT);
            statement.setString(2, "system");
            statement.executeUpdate();
        }
    }

    private static boolean tableExists(Context context, String expected) throws Exception {
        var connection = context.getConnection();
        var metadata = connection.getMetaData();
        try (var tables = metadata.getTables(connection.getCatalog(), null, "%", new String[]{"TABLE"})) {
            while (tables.next()) {
                String schema = tables.getString("TABLE_SCHEM");
                if (schema != null && schema.equalsIgnoreCase("information_schema")) continue;
                if (expected.equalsIgnoreCase(tables.getString("TABLE_NAME"))) return true;
            }
        }
        return false;
    }

    private static boolean columnExists(Context context, String table, String expected) throws Exception {
        var connection = context.getConnection();
        var metadata = connection.getMetaData();
        try (var tables = metadata.getTables(connection.getCatalog(), null, "%", new String[]{"TABLE"})) {
            while (tables.next()) {
                String actualTable = tables.getString("TABLE_NAME");
                if (!table.equalsIgnoreCase(actualTable)) continue;
                String schema = tables.getString("TABLE_SCHEM");
                try (var columns = metadata.getColumns(connection.getCatalog(), schema, actualTable, "%")) {
                    while (columns.next()) {
                        if (expected.equalsIgnoreCase(columns.getString("COLUMN_NAME"))) return true;
                    }
                }
            }
        }
        return false;
    }
}
