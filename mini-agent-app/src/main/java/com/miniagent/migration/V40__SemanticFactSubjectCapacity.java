package com.miniagent.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.List;
import java.util.Locale;

/** Increase semantic-fact subjects to fit planner-generated topics without truncation. */
public class V40__SemanticFactSubjectCapacity extends BaseJavaMigration {

    private static final String TABLE = "agent_semantic_facts";
    private static final String COLUMN = "subject";
    private static final int TARGET_LENGTH = 512;

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        DatabaseMetaData metadata = connection.getMetaData();
        String catalog = connection.getCatalog();
        String schema = connection.getSchema();
        String actualTable = findTable(metadata, catalog, schema);
        // On a fresh desktop install Hibernate creates baseline tables after Flyway.
        if (actualTable == null) return;

        ColumnInfo column = findSubjectColumn(metadata, catalog, schema, actualTable);
        if (column == null || column.length() >= TARGET_LENGTH) return;

        String quote = metadata.getIdentifierQuoteString();
        if (quote == null || quote.isBlank()) quote = "";
        boolean h2 = "H2".equalsIgnoreCase(metadata.getDatabaseProductName());
        String alteration = h2 ? " ALTER COLUMN " : " MODIFY COLUMN ";
        String ddl = "ALTER TABLE " + quote(actualTable, quote) + alteration
                + quote(column.name(), quote) + " VARCHAR(" + TARGET_LENGTH + ") NOT NULL";
        try (var statement = connection.createStatement()) {
            statement.execute(ddl);
        }
    }

    private static String findTable(DatabaseMetaData metadata, String catalog, String schema) throws Exception {
        for (String tablePattern : List.of(TABLE, TABLE.toLowerCase(Locale.ROOT), TABLE.toUpperCase(Locale.ROOT))) {
            try (ResultSet tables = metadata.getTables(catalog, schema, tablePattern, new String[]{"TABLE"})) {
                while (tables.next()) {
                    String name = tables.getString("TABLE_NAME");
                    if (TABLE.equalsIgnoreCase(name)) return name;
                }
            }
        }
        return null;
    }

    private static ColumnInfo findSubjectColumn(DatabaseMetaData metadata, String catalog,
                                                String schema, String table) throws Exception {
        for (String tablePattern : List.of(table, table.toLowerCase(Locale.ROOT), table.toUpperCase(Locale.ROOT))) {
            try (ResultSet columns = metadata.getColumns(catalog, schema, tablePattern, "%")) {
                while (columns.next()) {
                    String name = columns.getString("COLUMN_NAME");
                    if (COLUMN.equalsIgnoreCase(name)) {
                        return new ColumnInfo(name, columns.getInt("COLUMN_SIZE"), columns.getInt("NULLABLE"));
                    }
                }
            }
        }
        return null;
    }

    private static String quote(String identifier, String quote) {
        return quote.isEmpty() ? identifier : quote + identifier.replace(quote, quote + quote) + quote;
    }

    private record ColumnInfo(String name, int length, int nullable) {}
}
