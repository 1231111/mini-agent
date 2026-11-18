package com.miniagent.migration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.DatabaseMetaData;
import java.util.Locale;
import java.util.List;

/** Durable retries for subject erasure and an exact owner key for outcome cleanup. */
public class V37__SubjectErasureRetryAndExactOutcomeOwner extends BaseJavaMigration {
    @Override
    public String getDescription() {
        return "add subject erasure retry lease and exact outcome outbox owner";
    }

    @Override
    public void migrate(Context context) throws Exception {
        var connection = context.getConnection();
        addColumnIfMissing(connection, "memory_subject_erasure", "attempts",
                "ALTER TABLE memory_subject_erasure ADD COLUMN attempts INT NOT NULL DEFAULT 0");
        addColumnIfMissing(connection, "memory_subject_erasure", "next_attempt_at",
                "ALTER TABLE memory_subject_erasure ADD COLUMN next_attempt_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)");
        addColumnIfMissing(connection, "memory_subject_erasure", "lease_until",
                "ALTER TABLE memory_subject_erasure ADD COLUMN lease_until DATETIME(6) NULL");
        addColumnIfMissing(connection, "memory_subject_erasure", "claim_token",
                "ALTER TABLE memory_subject_erasure ADD COLUMN claim_token VARCHAR(36) NULL");
        createIndexIfMissing(connection, "memory_subject_erasure", "idx_mse_retry",
                "CREATE INDEX idx_mse_retry ON memory_subject_erasure (status,next_attempt_at,lease_until)");
        addColumnIfMissing(connection, "agent_memory_outcome_outbox", "user_id",
                "ALTER TABLE agent_memory_outcome_outbox ADD COLUMN user_id BIGINT NULL");

        ObjectMapper mapper = new ObjectMapper();
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE agent_memory_outcome_outbox SET user_id=? WHERE id=? AND user_id IS NULL")) {
            while (true) {
                boolean found = false;
                try (PreparedStatement select = connection.prepareStatement(
                        "SELECT id,payload_json FROM agent_memory_outcome_outbox"
                                + " WHERE user_id IS NULL ORDER BY id LIMIT 500");
                     ResultSet rows = select.executeQuery()) {
                    while (rows.next()) {
                        found = true;
                        long id = rows.getLong(1);
                        JsonNode root;
                        try {
                            root = mapper.readTree(rows.getString(2));
                        } catch (Exception malformed) {
                            throw new FlywayException("Cannot safely backfill owner for outcome outbox row " + id,
                                    malformed);
                        }
                        JsonNode userId = root == null ? null : root.get("userId");
                        if (userId == null || !userId.canConvertToLong() || userId.asLong() <= 0) {
                            throw new FlywayException("Outcome outbox row " + id + " has no valid userId");
                        }
                        update.setLong(1, userId.asLong());
                        update.setLong(2, id);
                        update.addBatch();
                    }
                }
                if (!found) break;
                update.executeBatch();
            }
        }
        try (var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE agent_memory_outcome_outbox MODIFY COLUMN user_id BIGINT NOT NULL");
        }
        createIndexIfMissing(connection, "agent_memory_outcome_outbox", "idx_amoo_owner",
                "CREATE INDEX idx_amoo_owner ON agent_memory_outcome_outbox (tenant_id,user_id)");
    }

    private static void addColumnIfMissing(java.sql.Connection connection, String table,
                                           String column, String ddl) throws Exception {
        if (!columnExists(connection.getMetaData(), connection.getCatalog(), table, column)) {
            try (var statement = connection.createStatement()) {
                statement.execute(ddl);
            }
        }
    }

    private static void createIndexIfMissing(java.sql.Connection connection, String table,
                                             String index, String ddl) throws Exception {
        if (!indexExists(connection.getMetaData(), connection.getCatalog(), table, index)) {
            try (var statement = connection.createStatement()) {
                statement.execute(ddl);
            }
        }
    }

    private static boolean columnExists(DatabaseMetaData metadata, String catalog,
                                        String table, String column) throws Exception {
        for (String tablePattern : tablePatterns(table)) {
            try (ResultSet rows = metadata.getColumns(catalog, null, tablePattern, null)) {
                while (rows.next()) {
                    if (column.equalsIgnoreCase(rows.getString("COLUMN_NAME"))) return true;
                }
            }
        }
        return false;
    }

    private static boolean indexExists(DatabaseMetaData metadata, String catalog,
                                       String table, String index) throws Exception {
        for (String tablePattern : tablePatterns(table)) {
            try (ResultSet rows = metadata.getIndexInfo(catalog, null, tablePattern, false, false)) {
                while (rows.next()) {
                    if (index.equalsIgnoreCase(rows.getString("INDEX_NAME"))) return true;
                }
            }
        }
        return false;
    }

    private static List<String> tablePatterns(String table) {
        return List.of(table, table.toLowerCase(Locale.ROOT), table.toUpperCase(Locale.ROOT))
                .stream().distinct().toList();
    }
}
