package com.miniagent.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Locale;

/** V22：本地 SOP 记下云端条目 id 与修订号，同步时按它覆盖内容。 */
public class V22__ProcedureCloudLink extends BaseJavaMigration {
    private static final String TABLE = "agent_procedures";
    private static final String ITEM_COLUMN = "cloud_item_id";
    private static final String REVISION_COLUMN = "cloud_revision_no";
    private static final String INDEX = "idx_ap_cloud_item";

    @Override
    public String getDescription() {
        return "link local procedures to cloud item revisions";
    }

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        if (!tableExists(connection, TABLE)) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            if (!columnExists(connection, ITEM_COLUMN)) {
                statement.execute("ALTER TABLE " + TABLE + " ADD COLUMN " + ITEM_COLUMN
                        + " BIGINT NULL");
            }
            if (!columnExists(connection, REVISION_COLUMN)) {
                statement.execute("ALTER TABLE " + TABLE + " ADD COLUMN " + REVISION_COLUMN
                        + " INT NULL");
            }
            if (!indexExists(connection)) {
                statement.execute("CREATE INDEX " + INDEX + " ON " + TABLE + " ("
                        + ITEM_COLUMN + ")");
            }
        }
    }

    private static boolean tableExists(Connection connection, String target) throws Exception {
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet rs = metadata.getTables(connection.getCatalog(), null, "%",
                new String[]{"TABLE"})) {
            while (rs.next()) {
                String schema = rs.getString("TABLE_SCHEM");
                String table = rs.getString("TABLE_NAME");
                if ((schema == null
                        || !schema.toLowerCase(Locale.ROOT).equals("information_schema"))
                        && target.equalsIgnoreCase(table)) {
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

    private static boolean indexExists(Connection connection) throws Exception {
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet rs = metadata.getIndexInfo(connection.getCatalog(), null, TABLE, false,
                false)) {
            while (rs.next()) {
                if (INDEX.equalsIgnoreCase(rs.getString("INDEX_NAME"))) {
                    return true;
                }
            }
        }
        return false;
    }
}
