package com.miniagent.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;

/** V11: attach consolidated episodes to their originating task. */
public class V11__TaskScopedEpisodes extends BaseJavaMigration {
    private static final String TABLE = "agent_episodes";
    private static final String INDEX = "idx_ae_task";

    @Override
    public String getDescription() {
        return "add task identity to episodes";
    }

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        String table = findTable(connection);
        if (table == null) {
            return;
        }
        if (!hasColumn(connection, table, "task_id")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE " + table + " ADD COLUMN task_id VARCHAR(128) NULL");
            }
        }
        if (!hasIndex(connection, table, INDEX)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE UNIQUE INDEX " + INDEX + " ON " + table
                        + " (tenant_id, session_id, task_id)");
            }
        }
    }

    private String findTable(Connection connection) throws Exception {
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet tables = metadata.getTables(
                connection.getCatalog(), connection.getSchema(), "%", new String[]{"TABLE"})) {
            while (tables.next()) {
                String schema = tables.getString("TABLE_SCHEM");
                if (schema != null && schema.equalsIgnoreCase("information_schema")) {
                    continue;
                }
                String name = tables.getString("TABLE_NAME");
                if (name != null && name.equalsIgnoreCase(TABLE)) {
                    return name;
                }
            }
        }
        return null;
    }

    private boolean hasColumn(Connection connection, String table, String column) throws Exception {
        try (ResultSet columns = connection.getMetaData().getColumns(
                connection.getCatalog(), connection.getSchema(), table, "%")) {
            while (columns.next()) {
                String name = columns.getString("COLUMN_NAME");
                if (name != null && name.equalsIgnoreCase(column)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean hasIndex(Connection connection, String table, String index) throws Exception {
        try (ResultSet indexes = connection.getMetaData().getIndexInfo(
                connection.getCatalog(), connection.getSchema(), table, false, false)) {
            while (indexes.next()) {
                String name = indexes.getString("INDEX_NAME");
                if (name != null && name.equalsIgnoreCase(index)) {
                    return true;
                }
            }
        }
        return false;
    }
}
