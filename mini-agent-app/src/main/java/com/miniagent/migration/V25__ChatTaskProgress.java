package com.miniagent.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import java.sql.ResultSet;
import java.sql.Statement;

/** Desktop fresh installs create chat_tasks later through Hibernate. */
public class V25__ChatTaskProgress extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        var connection = context.getConnection();
        String table = null;
        try (ResultSet tables = connection.getMetaData().getTables(connection.getCatalog(), null, "%", new String[]{"TABLE"})) {
            while (tables.next()) {
                if ("chat_tasks".equalsIgnoreCase(tables.getString("TABLE_NAME"))) {
                    table = tables.getString("TABLE_NAME");
                    break;
                }
            }
        }
        if (table == null) return;
        try (ResultSet columns = connection.getMetaData().getColumns(connection.getCatalog(), null, table, "%")) {
            while (columns.next()) if ("task_progress_json".equalsIgnoreCase(columns.getString("COLUMN_NAME"))) return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE chat_tasks ADD COLUMN task_progress_json LONGTEXT");
        }
    }
}
