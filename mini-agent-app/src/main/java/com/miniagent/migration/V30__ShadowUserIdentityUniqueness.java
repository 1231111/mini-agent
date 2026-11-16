package com.miniagent.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Desktop skips the MySQL V8 baseline; upgrades still need one row per external identity. */
public class V30__ShadowUserIdentityUniqueness extends BaseJavaMigration {
    @Override public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        String table = businessUsersTable(connection);
        // New desktop databases are subsequently initialized from the User entity.
        if (table == null) return;
        if (!V23__CloudMemoryAccessToken.columnExists(connection, "external_id")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE users ADD COLUMN external_id VARCHAR(128)");
            }
        }
        if (hasUniqueExternalIdentity(connection, table)) return;
        try (Statement statement = connection.createStatement(); ResultSet duplicates = statement.executeQuery(
                "SELECT external_id FROM users WHERE external_id IS NOT NULL "
                        + "GROUP BY external_id HAVING COUNT(*) > 1")) {
            if (duplicates.next()) throw new SQLException(
                    "Cannot enforce shadow user identity uniqueness: duplicate external identities exist. "
                            + "No users were deleted or merged; repair the identity mapping before retrying.");
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE UNIQUE INDEX ux_users_external_id ON users (external_id)");
        }
    }

    private static String businessUsersTable(Connection connection) throws Exception {
        try (ResultSet tables = connection.getMetaData().getTables(
                connection.getCatalog(), null, "%", new String[]{"TABLE"})) {
            String schema = connection.getSchema();
            while (tables.next()) {
                String actualSchema = tables.getString("TABLE_SCHEM");
                if ("users".equalsIgnoreCase(tables.getString("TABLE_NAME"))
                        && (schema == null || schema.isBlank() || actualSchema == null
                        || actualSchema.isBlank() || schema.equalsIgnoreCase(actualSchema))) {
                    return tables.getString("TABLE_NAME");
                }
            }
        }
        return null;
    }

    private static boolean hasUniqueExternalIdentity(Connection connection, String table) throws Exception {
        Map<String, List<String>> indexes = new LinkedHashMap<>();
        try (ResultSet entries = connection.getMetaData().getIndexInfo(
                connection.getCatalog(), connection.getSchema(), table, true, false)) {
            while (entries.next()) {
                String name = entries.getString("INDEX_NAME");
                String column = entries.getString("COLUMN_NAME");
                if (name != null && column != null && !entries.getBoolean("NON_UNIQUE")) {
                    indexes.computeIfAbsent(name, unused -> new ArrayList<>()).add(column);
                }
            }
        }
        return indexes.values().stream().anyMatch(columns -> columns.size() == 1
                && "external_id".equalsIgnoreCase(columns.getFirst()));
    }
}
