package com.miniagent.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Locale;

/** Add bounded, structured tool exchange history to assistant conversation rows. */
public class V33__PersistAgentToolHistory extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        String table = findTable(connection, "chat_messages");
        if (table == null) {
            throw new IllegalStateException("Required table chat_messages is missing");
        }
        if (findColumn(connection, table, "tool_history_json") == null) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE " + table + " ADD COLUMN tool_history_json LONGTEXT NULL");
            }
        }
        if (findColumn(connection, table, "tool_history_json") == null) {
            throw new IllegalStateException("Migration did not create chat_messages.tool_history_json");
        }
    }

    private static String findTable(Connection connection, String logicalName) throws Exception {
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet rows = metadata.getTables(connection.getCatalog(), connection.getSchema(), "%",
                new String[]{"TABLE"})) {
            while (rows.next()) {
                String schema = rows.getString("TABLE_SCHEM");
                if (schema != null && schema.toLowerCase(Locale.ROOT).equals("information_schema")) continue;
                String name = rows.getString("TABLE_NAME");
                if (name != null && name.equalsIgnoreCase(logicalName)) return name;
            }
        }
        return null;
    }

    private static String findColumn(Connection connection, String table, String logicalName) throws Exception {
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet rows = metadata.getColumns(connection.getCatalog(), connection.getSchema(), "%", "%")) {
            while (rows.next()) {
                String actualTable = rows.getString("TABLE_NAME");
                String actualColumn = rows.getString("COLUMN_NAME");
                if (actualTable != null && actualTable.equalsIgnoreCase(table)
                        && actualColumn != null && actualColumn.equalsIgnoreCase(logicalName)) {
                    return actualColumn;
                }
            }
        }
        return null;
    }
}
