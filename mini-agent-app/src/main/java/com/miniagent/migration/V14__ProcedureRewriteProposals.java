package com.miniagent.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Locale;

/** V14: persist reviewable, version-bound Procedure rewrite proposals. */
public class V14__ProcedureRewriteProposals extends BaseJavaMigration {
    private static final String TABLE = "agent_procedure_rewrite_proposals";

    @Override
    public String getDescription() {
        return "add reviewable procedure rewrite proposals";
    }

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        if (findTableName(connection, TABLE) != null) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE " + TABLE + " ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT,"
                    + "tenant_id VARCHAR(64) NOT NULL,"
                    + "user_id BIGINT NOT NULL,"
                    + "procedure_id BIGINT NOT NULL,"
                    + "base_version INT NOT NULL,"
                    + "episode_id BIGINT NOT NULL,"
                    + "procedure_name VARCHAR(256) NOT NULL,"
                    + "base_content_json LONGTEXT NOT NULL,"
                    + "proposed_content_json LONGTEXT NOT NULL,"
                    + "evidence_json LONGTEXT NOT NULL,"
                    + "status VARCHAR(16) NOT NULL DEFAULT 'PENDING',"
                    + "reviewed_by VARCHAR(128),"
                    + "reviewed_at DATETIME(6),"
                    + "created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),"
                    + "PRIMARY KEY (id),"
                    + "CONSTRAINT ux_aprp_source UNIQUE (tenant_id,procedure_id,base_version,episode_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
            statement.execute("CREATE INDEX idx_aprp_owner_status ON " + TABLE
                    + " (tenant_id,user_id,status,created_at)");
        }
    }

    private String findTableName(Connection connection, String logicalName) throws Exception {
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet tables = metadata.getTables(connection.getCatalog(), connection.getSchema(),
                "%", new String[]{"TABLE"})) {
            while (tables.next()) {
                String schema = tables.getString("TABLE_SCHEM");
                if (schema != null && schema.toLowerCase(Locale.ROOT).equals("information_schema")) {
                    continue;
                }
                String name = tables.getString("TABLE_NAME");
                if (name != null && name.equalsIgnoreCase(logicalName)) {
                    return name;
                }
            }
        }
        return null;
    }
}
