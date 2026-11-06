package com.miniagent.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Locale;

public class V20__CanaryAssignmentOutbox extends BaseJavaMigration {
    private static final String TABLE = "agent_canary_assignment_outbox";
    @Override public String getDescription() { return "add durable canary assignment and observation outbox"; }
    @Override public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        if (!tableExists(connection)) {
            createOutboxTable(connection);
        }
        addApplicationEvolutionColumns(connection);
        addDefinitionEvolutionColumns(connection);
    }

    private void createOutboxTable(Connection connection) throws Exception {
        try (Statement s = connection.createStatement()) {
            s.execute("CREATE TABLE " + TABLE + " ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT, tenant_id VARCHAR(64) NOT NULL, user_id BIGINT NOT NULL,"
                    + "session_id VARCHAR(100) NOT NULL, task_id BIGINT NOT NULL, node_id VARCHAR(128) NOT NULL,"
                    + "procedure_id BIGINT NOT NULL, procedure_version INT NOT NULL, proposal_id BIGINT NOT NULL,"
                    + "candidate_hash CHAR(64) NOT NULL, variant VARCHAR(16) NOT NULL, target_percent INT NOT NULL,"
                    + "bucket INT NOT NULL, router_attestation VARCHAR(512) NOT NULL, event_type VARCHAR(16) NOT NULL,"
                    + "passed BOOLEAN, observation_id VARCHAR(160), status VARCHAR(16) NOT NULL,"
                    + "attempts INT NOT NULL DEFAULT 0, next_attempt_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),"
                    + "lease_until DATETIME(6), claim_token VARCHAR(36), last_error VARCHAR(1000),"
                    + "created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),"
                    + "updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), PRIMARY KEY (id))");
            s.execute("CREATE UNIQUE INDEX ux_acao_assignment ON " + TABLE
                    + " (tenant_id,user_id,session_id,task_id,node_id,procedure_id,proposal_id,event_type)");
            s.execute("CREATE INDEX idx_acao_due ON " + TABLE + " (status,next_attempt_at)");
        }
    }
    private void addDefinitionEvolutionColumns(Connection connection) throws Exception {
        String table = "agent_procedure_definition_outbox";
        if (!tableExists(connection, table)) return;
        addColumnIfMissing(connection, table, "evolution_proposal_id", "BIGINT NULL");
        addColumnIfMissing(connection, table, "evolution_operation", "VARCHAR(16) NULL");
        addColumnIfMissing(connection, table, "evolution_candidate_hash", "CHAR(64) NULL");
    }

    private void addApplicationEvolutionColumns(Connection connection) throws Exception {
        String table = "agent_procedure_applications";
        if (!tableExists(connection, table)) return;
        addColumnIfMissing(connection, table, "evolution_proposal_id", "BIGINT NULL");
        addColumnIfMissing(connection, table, "evolution_candidate_hash", "CHAR(64) NULL");
        addColumnIfMissing(connection, table, "procedure_variant", "VARCHAR(16) NOT NULL DEFAULT 'BASELINE'");
        replaceApplicationAttemptIndex(connection, table);
    }

    private void replaceApplicationAttemptIndex(Connection connection, String table) throws Exception {
        String index = "ux_apa_attempt";
        String replacement = index + "_v20";
        if (hasIndex(connection, table, index)
                && hasIndexColumn(connection, table, index, "procedure_variant")) return;
        if (!hasIndex(connection, table, replacement)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE UNIQUE INDEX " + replacement + " ON " + table
                        + " (tenant_id,user_id,session_id,task_id,node_id,procedure_id,procedure_version,procedure_variant)");
            }
        } else if (!hasIndexColumn(connection, table, replacement, "procedure_variant")) {
            throw new IllegalStateException("V20 replacement index exists with an unexpected definition");
        }
        if (hasIndex(connection, table, index)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("DROP INDEX " + index + " ON " + table);
            }
        }
    }

    private boolean hasIndexColumn(Connection connection, String table, String index, String column) throws Exception {
        try (ResultSet rs = connection.getMetaData().getIndexInfo(connection.getCatalog(), null, table.toUpperCase(Locale.ROOT), false, false)) {
            while (rs.next()) {
                if (index.equalsIgnoreCase(rs.getString("INDEX_NAME"))
                        && column.equalsIgnoreCase(rs.getString("COLUMN_NAME"))) return true;
            }
        }
        return false;
    }

    private boolean hasIndex(Connection connection, String table, String index) throws Exception {
        try (ResultSet rs = connection.getMetaData().getIndexInfo(connection.getCatalog(), null, table.toUpperCase(Locale.ROOT), false, false)) {
            while (rs.next()) if (index.equalsIgnoreCase(rs.getString("INDEX_NAME"))) return true;
        }
        return false;
    }

    private void addColumnIfMissing(Connection connection, String table, String column, String definition) throws Exception {
        try (ResultSet rs = connection.getMetaData().getColumns(connection.getCatalog(), null, "%", "%")) {
            while (rs.next()) {
                if (table.equalsIgnoreCase(rs.getString("TABLE_NAME"))
                        && column.equalsIgnoreCase(rs.getString("COLUMN_NAME"))) return;
            }
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        }
    }

    private boolean tableExists(Connection c) throws Exception {
        return tableExists(c, TABLE);
    }

    private boolean tableExists(Connection c, String target) throws Exception {
        DatabaseMetaData md = c.getMetaData();
        try (ResultSet rs = md.getTables(c.getCatalog(), null, "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                String schema = rs.getString("TABLE_SCHEM"), table = rs.getString("TABLE_NAME");
                if ((schema == null || !schema.toLowerCase(Locale.ROOT).equals("information_schema"))
                        && target.equalsIgnoreCase(table)) return true;
            }
        }
        return false;
    }
}
