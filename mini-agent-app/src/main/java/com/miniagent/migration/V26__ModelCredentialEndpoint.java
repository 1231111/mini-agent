package com.miniagent.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.ResultSet;
import java.sql.Statement;

/** Persist credential destination separately from model presets, which may change after restart. */
public class V26__ModelCredentialEndpoint extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        var connection = context.getConnection();
        String currentSchema = connection.getSchema();
        String table = null;
        String schema = null;
        try (ResultSet tables = connection.getMetaData().getTables(
                connection.getCatalog(), null, "%", new String[]{"TABLE"})) {
            while (tables.next()) {
                String actualSchema = tables.getString("TABLE_SCHEM");
                boolean inCurrentSchema = currentSchema == null || currentSchema.isBlank()
                        || actualSchema == null || actualSchema.isBlank()
                        || currentSchema.equalsIgnoreCase(actualSchema);
                if ("user_model_config".equalsIgnoreCase(tables.getString("TABLE_NAME")) && inCurrentSchema) {
                    table = tables.getString("TABLE_NAME");
                    schema = actualSchema;
                    break;
                }
            }
        }
        // Fresh desktop databases are populated by Hibernate after Flyway runs.
        if (table == null) return;
        try (ResultSet columns = connection.getMetaData().getColumns(connection.getCatalog(), schema, table, "%")) {
            while (columns.next()) {
                if (table.equalsIgnoreCase(columns.getString("TABLE_NAME"))
                        && "custom_api_key_base_url".equalsIgnoreCase(columns.getString("COLUMN_NAME"))) return;
            }
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE user_model_config ADD COLUMN custom_api_key_base_url VARCHAR(512)");
        }
        // Existing keys deliberately remain unbound: their original destination cannot be reconstructed safely.
    }
}
