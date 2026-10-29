package com.miniagent.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Locale;

public class V15__TrustedProjectsAndProcedureHistory extends BaseJavaMigration {
    @Override
    public String getDescription() { return "add trusted projects and procedure revision history"; }

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        try (Statement statement = connection.createStatement()) {
            if (!columnExists(connection, "chat_conversations", "project_id")) {
                statement.execute("ALTER TABLE chat_conversations ADD COLUMN project_id VARCHAR(128)");
            }
            if (!tableExists(connection, "agent_projects")) {
                statement.execute("CREATE TABLE agent_projects ("
                        + "project_id VARCHAR(128) NOT NULL, tenant_id VARCHAR(64) NOT NULL,"
                        + "owner_user_id BIGINT NOT NULL, project_name VARCHAR(256) NOT NULL,"
                        + "created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),"
                        + "PRIMARY KEY (project_id), KEY idx_aproj_tenant_owner (tenant_id,owner_user_id)"
                        + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
            }
            if (!tableExists(connection, "agent_project_members")) {
                statement.execute("CREATE TABLE agent_project_members ("
                        + "project_id VARCHAR(128) NOT NULL, user_id BIGINT NOT NULL,"
                        + "role VARCHAR(16) NOT NULL DEFAULT 'MEMBER',"
                        + "PRIMARY KEY (project_id,user_id), KEY idx_apm_user (user_id,project_id),"
                        + "CONSTRAINT fk_apm_project FOREIGN KEY (project_id) REFERENCES agent_projects(project_id)"
                        + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
            }
            if (!tableExists(connection, "agent_procedure_revisions")) {
                statement.execute("CREATE TABLE agent_procedure_revisions ("
                        + "id BIGINT NOT NULL AUTO_INCREMENT, tenant_id VARCHAR(64) NOT NULL,"
                        + "procedure_id BIGINT NOT NULL, procedure_version INT NOT NULL,"
                        + "content_hash VARCHAR(64) NOT NULL, content_json LONGTEXT NOT NULL,"
                        + "source_type VARCHAR(24) NOT NULL, source_id VARCHAR(128), actor_user_id BIGINT,"
                        + "created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),"
                        + "PRIMARY KEY (id), UNIQUE KEY ux_apr_version (tenant_id,procedure_id,procedure_version),"
                        + "KEY idx_apr_procedure (tenant_id,procedure_id,created_at)"
                        + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
            }
            if (!tableExists(connection, "experience_clusters")) {
                statement.execute("CREATE TABLE experience_clusters ("
                        + "id BIGINT NOT NULL AUTO_INCREMENT, tenant_id VARCHAR(64) NOT NULL,"
                        + "owner_user_id BIGINT NOT NULL, scope_type VARCHAR(16) NOT NULL,"
                        + "scope_id VARCHAR(128) NOT NULL, cluster_key VARCHAR(128) NOT NULL,"
                        + "label VARCHAR(256) NOT NULL, version INT NOT NULL DEFAULT 1,"
                        + "status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',"
                        + "created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),"
                        + "updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),"
                        + "PRIMARY KEY (id), UNIQUE KEY ux_ec_key (tenant_id,scope_type,scope_id,cluster_key),"
                        + "KEY idx_ec_owner (tenant_id,owner_user_id,status)"
                        + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
            }
            if (!tableExists(connection, "experience_cluster_members")) {
                statement.execute("CREATE TABLE experience_cluster_members ("
                        + "id BIGINT NOT NULL AUTO_INCREMENT, tenant_id VARCHAR(64) NOT NULL,"
                        + "cluster_id BIGINT NOT NULL, episode_id BIGINT NOT NULL,"
                        + "created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),"
                        + "PRIMARY KEY (id), UNIQUE KEY ux_ecm_episode (tenant_id,episode_id),"
                        + "KEY idx_ecm_cluster (tenant_id,cluster_id),"
                        + "CONSTRAINT fk_ecm_cluster FOREIGN KEY (cluster_id) REFERENCES experience_clusters(id)"
                        + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
            }
        }
    }

    private boolean tableExists(Connection connection, String name) throws Exception {
        try (ResultSet tables = connection.getMetaData().getTables(connection.getCatalog(), null, "%", new String[]{"TABLE"})) {
            while (tables.next()) {
                String schema = tables.getString("TABLE_SCHEM");
                String table = tables.getString("TABLE_NAME");
                if ((schema == null || !schema.toLowerCase(Locale.ROOT).equals("information_schema"))
                        && table != null && table.equalsIgnoreCase(name)) return true;
            }
        }
        return false;
    }

    private boolean columnExists(Connection connection, String table, String column) throws Exception {
        try (ResultSet columns = connection.getMetaData().getColumns(connection.getCatalog(), null, "%", "%")) {
            while (columns.next()) {
                if (table.equalsIgnoreCase(columns.getString("TABLE_NAME"))
                        && column.equalsIgnoreCase(columns.getString("COLUMN_NAME"))) return true;
            }
        }
        return false;
    }
}
