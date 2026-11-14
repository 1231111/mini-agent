package com.miniagent.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import java.sql.Connection;
import java.sql.ResultSet;

/** Align fresh desktop schemas with production credential capacity; never infer model windows. */
public class V27__UserModelContextWindowAndKeyLength extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        String currentSchema = connection.getSchema(), table = null, schema = null;
        try (ResultSet tables = connection.getMetaData().getTables(connection.getCatalog(), null, "%", new String[]{"TABLE"})) {
            while (tables.next()) {
                String actualSchema = tables.getString("TABLE_SCHEM");
                boolean current = currentSchema == null || currentSchema.isBlank() || actualSchema == null
                        || actualSchema.isBlank() || currentSchema.equalsIgnoreCase(actualSchema);
                if (current && "user_model_config".equalsIgnoreCase(tables.getString("TABLE_NAME"))) {
                    table = tables.getString("TABLE_NAME"); schema = actualSchema; break;
                }
            }
        }
        // Flyway runs before Hibernate creates tables on a fresh desktop installation.
        if (table == null) return;
        boolean hasWindow = false;
        String keyColumn = null;
        int keyLength = 0;
        try (ResultSet columns = connection.getMetaData().getColumns(connection.getCatalog(), schema, table, "%")) {
            while (columns.next()) {
                if (!table.equalsIgnoreCase(columns.getString("TABLE_NAME"))) continue;
                String name = columns.getString("COLUMN_NAME");
                if ("custom_context_window_tokens".equalsIgnoreCase(name)) hasWindow = true;
                if ("custom_api_key".equalsIgnoreCase(name)) {
                    keyColumn = name; keyLength = columns.getInt("COLUMN_SIZE");
                }
            }
        }
        String quote = connection.getMetaData().getIdentifierQuoteString().trim();
        String tableSql = quoted(table, quote);
        try (var statement = connection.createStatement()) {
            if (!hasWindow) statement.execute("ALTER TABLE " + tableSql + " ADD COLUMN custom_context_window_tokens INTEGER");
            if (keyColumn != null && keyLength > 0 && keyLength < 1024) {
                boolean h2 = "H2".equalsIgnoreCase(connection.getMetaData().getDatabaseProductName());
                statement.execute("ALTER TABLE " + tableSql + (h2 ? " ALTER COLUMN " : " MODIFY COLUMN ")
                        + quoted(keyColumn, quote) + " VARCHAR(1024)");
            }
        }
        // Null windows retain their meaning: custom model capacity has not been confirmed.
    }

    private static String quoted(String identifier, String quote) {
        return quote + identifier.replace(quote, quote + quote) + quote;
    }
}
