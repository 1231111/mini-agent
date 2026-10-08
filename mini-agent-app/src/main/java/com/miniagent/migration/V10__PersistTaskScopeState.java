package com.miniagent.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;

/** V10：持久化任务作用域指针与会话 exec 策略覆盖（H2 / MySQL 通用）。 */
public class V10__PersistTaskScopeState extends BaseJavaMigration {

    private static final String TODO_TABLE = "agent_session_todos";
    private static final String PERMISSION_TABLE =
            "agent_session_permissions";

    @Override
    public String getDescription() {
        return "persist task scope and permission override state";
    }

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        String todoTable = findTableName(connection, TODO_TABLE);
        if (todoTable != null) {
            migrateTaskScope(connection, todoTable);
        }
        String permissionTable =
                findTableName(connection, PERMISSION_TABLE);
        if (permissionTable != null) {
            addColumnIfMissing(
                    connection,
                    permissionTable,
                    "exec_policy_override",
                    "VARCHAR(16) NULL");
        }
    }

    private void migrateTaskScope(
            Connection connection, String table) throws Exception {
        addColumnIfMissing(
                connection,
                table,
                "current_task_id",
                "BIGINT NOT NULL DEFAULT 0");
        addColumnIfMissing(
                connection,
                table,
                "paused_task_id",
                "BIGINT NULL");
        addColumnIfMissing(
                connection,
                table,
                "scope_sequence",
                "BIGINT NOT NULL DEFAULT 0");

        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "UPDATE " + table
                            + " SET current_task_id = 0"
                            + " WHERE current_task_id IS NULL");
            statement.executeUpdate(
                    "UPDATE " + table
                            + " SET scope_sequence = current_task_id"
                            + " WHERE scope_sequence IS NULL"
                            + " OR scope_sequence < current_task_id");
            statement.executeUpdate(
                    "UPDATE " + table
                            + " SET version = 0 WHERE version IS NULL");
        }
    }

    private void addColumnIfMissing(
            Connection connection,
            String table,
            String column,
            String definition) throws Exception {
        if (hasColumn(connection, table, column)) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute(
                    "ALTER TABLE " + table
                            + " ADD COLUMN " + column + " " + definition);
        }
    }

    private String findTableName(
            Connection connection, String logicalName) throws Exception {
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet rs = metaData.getTables(
                connection.getCatalog(),
                connection.getSchema(),
                "%",
                new String[]{"TABLE"})) {
            while (rs.next()) {
                String schema = rs.getString("TABLE_SCHEM");
                if (schema != null
                        && schema.equalsIgnoreCase("information_schema")) {
                    continue;
                }
                String name = rs.getString("TABLE_NAME");
                if (name.equalsIgnoreCase(logicalName)) {
                    return name;
                }
            }
        }
        return null;
    }

    private boolean hasColumn(
            Connection connection, String table, String logicalName)
            throws Exception {
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet rs = metaData.getColumns(
                connection.getCatalog(),
                connection.getSchema(),
                table,
                "%")) {
            while (rs.next()) {
                String name = rs.getString("COLUMN_NAME");
                if (name != null && name.equalsIgnoreCase(logicalName)) {
                    return true;
                }
            }
        }
        return false;
    }
}
