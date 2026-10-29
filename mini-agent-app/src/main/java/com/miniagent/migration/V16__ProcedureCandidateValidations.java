package com.miniagent.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

/** Persist independent validation evidence for rewrite candidates. */
public class V16__ProcedureCandidateValidations extends BaseJavaMigration {
    @Override
    public String getDescription() { return "add procedure candidate validation records"; }

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        if (tableExists(connection, "agent_procedure_candidate_validations")) return;
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE agent_procedure_candidate_validations ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT, tenant_id VARCHAR(64) NOT NULL,"
                    + "proposal_id BIGINT NOT NULL, candidate_hash VARCHAR(64) NOT NULL,"
                    + "verifier_user_id BIGINT NOT NULL, passed BOOLEAN NOT NULL,"
                    + "evidence_json LONGTEXT NOT NULL,"
                    + "created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),"
                    + "PRIMARY KEY (id),"
                    + "CONSTRAINT ux_apcv_proposal_verifier UNIQUE (proposal_id,verifier_user_id),"
                    + "KEY idx_apcv_proposal (tenant_id,proposal_id,created_at),"
                    + "CONSTRAINT fk_apcv_proposal FOREIGN KEY (proposal_id) "
                    + "REFERENCES agent_procedure_rewrite_proposals(id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
        }
    }

    private boolean tableExists(Connection connection, String name) throws Exception {
        try (ResultSet tables = connection.getMetaData().getTables(
                connection.getCatalog(), null, "%", new String[]{"TABLE"})) {
            while (tables.next()) {
                String table = tables.getString("TABLE_NAME");
                if (table != null && table.equalsIgnoreCase(name)) return true;
            }
        }
        return false;
    }
}
