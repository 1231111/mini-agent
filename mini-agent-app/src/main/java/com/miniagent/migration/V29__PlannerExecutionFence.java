package com.miniagent.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.ResultSet;

/** Backfills a separately comparable execution identity without rewriting historical snapshots. */
public class V29__PlannerExecutionFence extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        var connection = context.getConnection();
        String table = null, schema = connection.getSchema();
        try (ResultSet tables = connection.getMetaData().getTables(connection.getCatalog(), schema, "%", new String[]{"TABLE"})) {
            while (tables.next()) if ("agent_session_planner".equalsIgnoreCase(tables.getString("TABLE_NAME"))) {
                table = tables.getString("TABLE_NAME"); break;
            }
        }
        // A fresh desktop database gets its base tables from Hibernate after Flyway.
        if (table == null) return;
        boolean present = false;
        try (ResultSet columns = connection.getMetaData().getColumns(connection.getCatalog(), schema, table, "%")) {
            while (columns.next()) if ("execution_id".equalsIgnoreCase(columns.getString("COLUMN_NAME"))) present = true;
        }
        if (!present) try (var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE agent_session_planner ADD COLUMN execution_id VARCHAR(128)");
        }
        ObjectMapper json = new ObjectMapper();
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT session_id,state_json FROM agent_session_planner WHERE execution_id IS NULL");
             var update = connection.prepareStatement("UPDATE agent_session_planner SET execution_id=? WHERE session_id=? AND execution_id IS NULL")) {
            while (rows.next()) {
                com.fasterxml.jackson.databind.JsonNode snapshot;
                try { snapshot = json.readTree(rows.getString("state_json")); }
                catch (Exception corruptHistoricalSnapshot) { continue; }
                var execution = snapshot == null ? null : snapshot.get("executionId");
                // Preserve absent/null legacy identities; never invent an execution.
                if (execution == null || execution.isNull()) continue;
                // Leave malformed historical streams fenced by null metadata;
                // one corrupt task must not prevent every other task from opening.
                if (!execution.isTextual() || execution.textValue().length() > 128) continue;
                update.setString(1, execution.textValue()); update.setString(2, rows.getString("session_id"));
                update.executeUpdate();
            }
        }
    }
}
