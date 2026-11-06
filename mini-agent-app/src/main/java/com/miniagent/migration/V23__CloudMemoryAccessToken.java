package com.miniagent.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;

/** V23: persist the Account tenant mapping and memory access token for shadow users. */
public class V23__CloudMemoryAccessToken extends BaseJavaMigration {
    private static final String TABLE = "users";

    @Override
    public String getDescription() {
        return "store cloud memory access identity";
    }

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        if (!tableExists(connection)) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            if (!columnExists(connection, "cloud_tenant_id")) {
                statement.execute("ALTER TABLE " + TABLE + " ADD COLUMN cloud_tenant_id VARCHAR(128)");
            }
            if (!columnExists(connection, "memory_access_token")) {
                statement.execute("ALTER TABLE " + TABLE + " ADD COLUMN memory_access_token VARCHAR(4096)");
            }
            if (!columnExists(connection, "memory_refresh_credential")) {
                statement.execute("ALTER TABLE " + TABLE + " ADD COLUMN memory_refresh_credential VARCHAR(128)");
            }
        }
    }

    private static boolean tableExists(Connection connection) throws Exception {
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet rs = metadata.getTables(connection.getCatalog(), null, "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                if (TABLE.equalsIgnoreCase(rs.getString("TABLE_NAME"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean columnExists(Connection connection, String column) throws Exception {
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet rs = metadata.getColumns(connection.getCatalog(), null, "%", "%")) {
            while (rs.next()) {
                if (TABLE.equalsIgnoreCase(rs.getString("TABLE_NAME"))
                        && column.equalsIgnoreCase(rs.getString("COLUMN_NAME"))) {
                    return true;
                }
            }
        }
        return false;
    }
}
