package com.miniagent.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Locale;

/** V12：USER scope Procedure 的版本、结算计数与节点应用尝试表。 */
public class V12__ProcedureApplicationSettlement extends BaseJavaMigration {
    private static final String PROCEDURES = "agent_procedures";
    private static final String APPLICATIONS = "agent_procedure_applications";
    private static final String UNIQUE_INDEX = "ux_apa_attempt";

    @Override
    public String getDescription() {
        return "add procedure versions and terminal application settlement";
    }

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        String procedureTable = findTableName(connection, PROCEDURES);
        if (procedureTable != null) {
            addColumnIfMissing(connection, procedureTable, "procedure_version",
                    "INT NOT NULL DEFAULT 1");
            addColumnIfMissing(connection, procedureTable, "success_count",
                    "BIGINT NOT NULL DEFAULT 0");
            addColumnIfMissing(connection, procedureTable, "attempt_count",
                    "BIGINT NOT NULL DEFAULT 0");
        }

        if (findTableName(connection, APPLICATIONS) == null) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE " + APPLICATIONS + " ("
                        + "id BIGINT NOT NULL AUTO_INCREMENT,"
                        + "tenant_id VARCHAR(64) NOT NULL,"
                        + "user_id BIGINT NOT NULL,"
                        + "session_id VARCHAR(100) NOT NULL,"
                        + "task_id BIGINT NOT NULL,"
                        + "node_id VARCHAR(128) NOT NULL,"
                        + "procedure_id BIGINT NOT NULL,"
                        + "procedure_version INT NOT NULL,"
                        + "action_id VARCHAR(128) NOT NULL,"
                        + "result VARCHAR(16) NOT NULL,"
                        + "created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),"
                        + "PRIMARY KEY (id),"
                        + "CONSTRAINT ck_apa_result CHECK (result IN ('UNKNOWN','SUCCESS','FAILURE'))"
                        + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
            }
        }
        String applicationTable = findTableName(connection, APPLICATIONS);
        if (!hasIndex(connection, applicationTable, UNIQUE_INDEX)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE UNIQUE INDEX " + UNIQUE_INDEX + " ON "
                        + applicationTable + " (tenant_id,user_id,session_id,task_id,node_id,"
                        + "procedure_id,procedure_version)");
            }
        }
        if (!hasIndex(connection, applicationTable, "idx_apa_procedure")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE INDEX idx_apa_procedure ON " + applicationTable
                        + " (tenant_id,user_id,procedure_id,procedure_version)");
            }
        }
    }

    private void addColumnIfMissing(Connection connection, String table, String column,
                                    String definition) throws Exception {
        if (hasColumn(connection, table, column)) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
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

    private boolean hasColumn(Connection connection, String table, String column) throws Exception {
        try (ResultSet columns = connection.getMetaData().getColumns(
                connection.getCatalog(), connection.getSchema(), table, "%")) {
            while (columns.next()) {
                String name = columns.getString("COLUMN_NAME");
                if (name != null && name.equalsIgnoreCase(column)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean hasIndex(Connection connection, String table, String index) throws Exception {
        try (ResultSet indexes = connection.getMetaData().getIndexInfo(
                connection.getCatalog(), connection.getSchema(), table, false, false)) {
            while (indexes.next()) {
                String name = indexes.getString("INDEX_NAME");
                if (name != null && name.equalsIgnoreCase(index)) {
                    return true;
                }
            }
        }
        return false;
    }
}
