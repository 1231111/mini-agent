package com.miniagent.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Locale;

public class V19__FailureEvidenceOutbox extends BaseJavaMigration {
    private static final String TABLE = "agent_failure_evidence_outbox";
    @Override public String getDescription() { return "add durable failure evidence outbox"; }
    @Override public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        if (tableExists(connection)) return;
        try (Statement s = connection.createStatement()) {
            s.execute("CREATE TABLE " + TABLE + " ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT, tenant_id VARCHAR(64) NOT NULL, user_id BIGINT NOT NULL,"
                    + "session_id VARCHAR(100) NOT NULL, task_id BIGINT NOT NULL, node_id VARCHAR(128) NOT NULL,"
                    + "procedure_id BIGINT NOT NULL, procedure_version INT NOT NULL, failure_code VARCHAR(128) NOT NULL,"
                    + "task_summary VARCHAR(500) NOT NULL, resolution TEXT NOT NULL, status VARCHAR(16) NOT NULL,"
                    + "attempts INT NOT NULL DEFAULT 0, next_attempt_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                    + "lease_until DATETIME(6), claim_token VARCHAR(36), last_error VARCHAR(1000), "
                    + "created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                    + "updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY (id))");
            s.execute("CREATE UNIQUE INDEX ux_afeo_source ON " + TABLE
                    + " (tenant_id, user_id, session_id, task_id, node_id, procedure_id, procedure_version)");
            s.execute("CREATE INDEX idx_afeo_due ON " + TABLE + " (status, next_attempt_at)");
        }
    }
    private boolean tableExists(Connection c) throws Exception {
        DatabaseMetaData md = c.getMetaData();
        try (ResultSet rs = md.getTables(c.getCatalog(), null, "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                String schema = rs.getString("TABLE_SCHEM"), table = rs.getString("TABLE_NAME");
                if ((schema == null || !schema.toLowerCase(Locale.ROOT).equals("information_schema"))
                        && TABLE.equalsIgnoreCase(table)) return true;
            }
        }
        return false;
    }
}
