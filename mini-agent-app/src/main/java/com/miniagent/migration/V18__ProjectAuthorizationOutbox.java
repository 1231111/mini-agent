package com.miniagent.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Locale;

public class V18__ProjectAuthorizationOutbox extends BaseJavaMigration {
    private static final String OUTBOX = "agent_project_authorization_outbox";

    @Override
    public String getDescription() {
        return "add project authorization snapshot outbox";
    }

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        try (Statement statement = connection.createStatement()) {
            if (!columnExists(connection, "agent_projects", "authorization_version")) {
                statement.execute("ALTER TABLE agent_projects ADD COLUMN authorization_version BIGINT NOT NULL DEFAULT 1");
            }
            if (!tableExists(connection, OUTBOX)) {
                statement.execute("CREATE TABLE " + OUTBOX + " ("
                        + "id BIGINT NOT NULL AUTO_INCREMENT,"
                        + "tenant_id VARCHAR(64) NOT NULL,"
                        + "project_id VARCHAR(128) NOT NULL,"
                        + "owner_user_id BIGINT NOT NULL,"
                        + "member_user_ids_json LONGTEXT NOT NULL,"
                        + "authorization_version BIGINT NOT NULL,"
                        + "status VARCHAR(16) NOT NULL,"
                        + "attempts INT NOT NULL DEFAULT 0,"
                        + "next_attempt_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                        + "last_error VARCHAR(1000),"
                        + "created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                        + "updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                        + "PRIMARY KEY (id),"
                        + "CONSTRAINT ux_apao_version UNIQUE (tenant_id,project_id,authorization_version)"
                        + ")");
                statement.execute("CREATE INDEX idx_apao_pending ON " + OUTBOX + " (status,next_attempt_at)");
            }
        }
    }

    private boolean tableExists(Connection connection, String name) throws Exception {
        try (ResultSet tables = connection.getMetaData().getTables(connection.getCatalog(), null, "%", new String[]{"TABLE"})) {
            while (tables.next()) {
                String schema = tables.getString("TABLE_SCHEM");
                String table = tables.getString("TABLE_NAME");
                if ((schema == null || !schema.toLowerCase(Locale.ROOT).equals("information_schema"))
                        && name.equalsIgnoreCase(table)) return true;
            }
        }
        return false;
    }

    private boolean columnExists(Connection connection, String table, String column) throws Exception {
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet columns = metadata.getColumns(connection.getCatalog(), null, "%", "%")) {
            while (columns.next()) {
                if (table.equalsIgnoreCase(columns.getString("TABLE_NAME"))
                        && column.equalsIgnoreCase(columns.getString("COLUMN_NAME"))) return true;
            }
        }
        return false;
    }
}
