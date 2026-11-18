package com.miniagent.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.ResultSet;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Privacy-preserving operator scoping for unresolved durable action receipts. */
public class V31__ActionReceiptSessionScopeHash extends BaseJavaMigration {
    private static final String TABLE = "agent_action_receipt";
    private static final String INDEX = "ix_action_receipt_status_time_key";

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        String tableName = findTableName(connection);
        if (tableName == null) throw new java.sql.SQLException("Required agent_action_receipt table is missing");
        if (!columnExists(connection, tableName, "session_id_hash")) {
            try (var statement = connection.createStatement()) {
                // Legacy rows remain NULL: the old full-key digest is irreversible.
                statement.execute("ALTER TABLE " + TABLE + " ADD COLUMN session_id_hash VARCHAR(64)");
            }
        }
        if (!hasOrderedStatusIndex(connection, tableName)) {
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE INDEX " + INDEX
                        + " ON " + TABLE + " (execution_status, timestamp_ms, key_digest)");
            }
        }
    }

    private static String findTableName(Connection connection) throws Exception {
        try (ResultSet tables = connection.getMetaData().getTables(
                connection.getCatalog(), connection.getSchema(), "%", new String[]{"TABLE"})) {
            while (tables.next()) {
                String tableName = tables.getString("TABLE_NAME");
                if (TABLE.equalsIgnoreCase(tableName)) return tableName;
            }
            return null;
        }
    }

    private static boolean columnExists(Connection connection, String tableName, String name) throws Exception {
        try (ResultSet columns = connection.getMetaData().getColumns(
                connection.getCatalog(), connection.getSchema(), tableName, "%")) {
            while (columns.next()) {
                if (tableName.equalsIgnoreCase(columns.getString("TABLE_NAME"))
                        && name.equalsIgnoreCase(columns.getString("COLUMN_NAME"))) return true;
            }
        }
        return false;
    }

    private static boolean hasOrderedStatusIndex(Connection connection, String tableName) throws Exception {
        Map<String, Map<Short, String>> indexes = new LinkedHashMap<>();
        try (ResultSet rows = connection.getMetaData().getIndexInfo(
                connection.getCatalog(), connection.getSchema(), tableName, false, false)) {
            while (rows.next()) {
                String index = rows.getString("INDEX_NAME");
                String column = rows.getString("COLUMN_NAME");
                if (index != null && column != null && tableName.equalsIgnoreCase(rows.getString("TABLE_NAME"))) {
                    indexes.computeIfAbsent(index, ignored -> new LinkedHashMap<>())
                            .put(rows.getShort("ORDINAL_POSITION"), column);
                }
            }
        }
        return indexes.values().stream().map(index -> index.entrySet().stream()
                        .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                        .map(Map.Entry::getValue).toList())
                .anyMatch(columns -> columns.size() == 3
                        && "execution_status".equalsIgnoreCase(columns.get(0))
                        && "timestamp_ms".equalsIgnoreCase(columns.get(1))
                        && "key_digest".equalsIgnoreCase(columns.get(2)));
    }
}
