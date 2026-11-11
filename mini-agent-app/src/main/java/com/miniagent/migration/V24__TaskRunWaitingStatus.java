package com.miniagent.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extend desktop H2 task-run enums without changing the MySQL VARCHAR baseline. */
public class V24__TaskRunWaitingStatus extends BaseJavaMigration {
    private static final String TABLE = "agent_task_runs";
    private static final Set<String> LEGACY = Set.of(
            "RUNNING", "COMPLETED", "FAILED", "CANCELLED", "INTERRUPTED");
    private static final Pattern STATUS_CHECK = Pattern.compile(
            "(?is)^\\(*\\s*(?:\"status\"|status)\\s+IN\\s*\\((.*?)\\)\\s*\\)*$");
    private static final Pattern ENUM_VALUES = Pattern.compile("\\s*'([^']*)'\\s*(?:,|$)");

    @Override
    public String getDescription() {
        return "allow waiting status in legacy desktop task run enums";
    }

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        if (!"H2".equalsIgnoreCase(connection.getMetaData().getDatabaseProductName())) {
            return;
        }
        String schema = connection.getSchema();
        if (schema == null || schema.isBlank()) return;
        String table = null;
        String column = null;
        String type = null;
        String enumId = null;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT TABLE_NAME, COLUMN_NAME, DATA_TYPE, DTD_IDENTIFIER "
                        + "FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = ? "
                        + "AND LOWER(TABLE_NAME) = ? AND LOWER(COLUMN_NAME) = 'status'")) {
            statement.setString(1, schema);
            statement.setString(2, TABLE);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return; // Flyway runs before Hibernate on a fresh installation.
                table = rows.getString("TABLE_NAME");
                column = rows.getString("COLUMN_NAME");
                type = rows.getString("DATA_TYPE");
                enumId = rows.getString("DTD_IDENTIFIER");
            }
        }
        String qualified = quote(schema) + "." + quote(table);
        if ("ENUM".equalsIgnoreCase(type)) {
            List<String> values = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT VALUE_NAME FROM INFORMATION_SCHEMA.ENUM_VALUES "
                            + "WHERE OBJECT_SCHEMA = ? AND OBJECT_NAME = ? "
                            + "AND OBJECT_TYPE = 'TABLE' AND ENUM_IDENTIFIER = ? ORDER BY VALUE_ORDINAL")) {
                statement.setString(1, schema);
                statement.setString(2, table);
                statement.setString(3, enumId);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) values.add(rows.getString(1));
                }
            }
            if (values.size() == LEGACY.size() && new HashSet<>(values).equals(LEGACY)) {
                values.add("WAITING");
                try (Statement statement = connection.createStatement()) {
                    // SET DATA TYPE preserves the existing default, NOT NULL, and other constraints.
                    statement.execute("ALTER TABLE " + qualified + " ALTER COLUMN " + quote(column)
                            + " SET DATA TYPE ENUM(" + literals(values) + ")");
                }
            }
        }

        // Earlier Hibernate versions used VARCHAR plus an IN CHECK instead of H2 ENUM.
        // Match only the exact legacy status set; leave every other CHECK unchanged.
        List<LegacyCheck> checks = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT TC.CONSTRAINT_NAME, CC.CHECK_CLAUSE FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS TC "
                        + "JOIN INFORMATION_SCHEMA.CHECK_CONSTRAINTS CC "
                        + "ON CC.CONSTRAINT_CATALOG = TC.CONSTRAINT_CATALOG "
                        + "AND CC.CONSTRAINT_SCHEMA = TC.CONSTRAINT_SCHEMA "
                        + "AND CC.CONSTRAINT_NAME = TC.CONSTRAINT_NAME "
                        + "WHERE TC.TABLE_SCHEMA = ? AND TC.TABLE_NAME = ? AND TC.CONSTRAINT_TYPE = 'CHECK'")) {
            statement.setString(1, schema);
            statement.setString(2, table);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    List<String> values = statusValues(rows.getString("CHECK_CLAUSE"));
                    if (values.size() == LEGACY.size() && new HashSet<>(values).equals(LEGACY)) {
                        checks.add(new LegacyCheck(rows.getString("CONSTRAINT_NAME"), values));
                    }
                }
            }
        }
        for (LegacyCheck check : checks) {
            List<String> values = new ArrayList<>(check.values());
            values.add("WAITING");
            String replacement = check.name().substring(0, Math.min(230, check.name().length()))
                    + "_WAITING_V24";
            String clause = quote(column) + " IN (" + literals(values) + ")";
            try (Statement statement = connection.createStatement()) {
                // Install the widened guard before removing the old one. A failed DDL
                // must never leave task statuses unvalidated, even on H2's auto-commit DDL.
                statement.execute("ALTER TABLE " + qualified + " ADD CONSTRAINT IF NOT EXISTS "
                        + quote(replacement) + " CHECK (" + clause + ")");
                verifyReplacement(connection, schema, table, replacement, values);
                statement.execute("ALTER TABLE " + qualified + " DROP CONSTRAINT " + quote(check.name()));
            }
        }
    }

    private static void verifyReplacement(Connection connection, String schema, String table, String name,
                                          List<String> expected) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT CC.CHECK_CLAUSE FROM INFORMATION_SCHEMA.CHECK_CONSTRAINTS CC "
                        + "JOIN INFORMATION_SCHEMA.TABLE_CONSTRAINTS TC "
                        + "ON TC.CONSTRAINT_CATALOG = CC.CONSTRAINT_CATALOG "
                        + "AND TC.CONSTRAINT_SCHEMA = CC.CONSTRAINT_SCHEMA "
                        + "AND TC.CONSTRAINT_NAME = CC.CONSTRAINT_NAME "
                        + "WHERE CC.CONSTRAINT_SCHEMA = ? AND CC.CONSTRAINT_NAME = ? AND TC.TABLE_NAME = ?")) {
            statement.setString(1, schema);
            statement.setString(2, name);
            statement.setString(3, table);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next() || !new HashSet<>(statusValues(rows.getString(1)))
                        .equals(new HashSet<>(expected))) {
                    throw new IllegalStateException("Unexpected existing task-run CHECK: " + name);
                }
            }
        }
    }

    private static List<String> statusValues(String clause) {
        if (clause == null) return List.of();
        Matcher check = STATUS_CHECK.matcher(clause);
        if (!check.matches()) return List.of();
        String source = check.group(1);
        Matcher value = ENUM_VALUES.matcher(source);
        List<String> values = new ArrayList<>();
        int cursor = 0;
        while (value.find()) {
            if (value.start() != cursor) return List.of();
            values.add(value.group(1));
            cursor = value.end();
        }
        return cursor == source.length() ? values : List.of();
    }

    private static String literals(List<String> values) {
        return values.stream().map(value -> "'" + value.replace("'", "''") + "'")
                .collect(java.util.stream.Collectors.joining(","));
    }

    private static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private record LegacyCheck(String name, List<String> values) {}
}
