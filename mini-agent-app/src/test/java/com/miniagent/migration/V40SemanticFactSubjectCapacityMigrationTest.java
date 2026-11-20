package com.miniagent.migration;

import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class V40SemanticFactSubjectCapacityMigrationTest {

    @Test
    void widensExistingSubjectWithoutLosingRowsAndAccepts259Characters() throws Exception {
        try (var db = DriverManager.getConnection(
                "jdbc:h2:mem:semantic-subject-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1")) {
            db.createStatement().execute("CREATE TABLE agent_semantic_facts (id BIGINT PRIMARY KEY, subject VARCHAR(256) NOT NULL)");
            db.createStatement().execute("INSERT INTO agent_semantic_facts(id,subject) VALUES(1,'existing fact')");
            Context context = mock(Context.class);
            when(context.getConnection()).thenReturn(db);

            V40__SemanticFactSubjectCapacity migration = new V40__SemanticFactSubjectCapacity();
            migration.migrate(context);
            migration.migrate(context);

            try (var columns = db.getMetaData().getColumns(db.getCatalog(), db.getSchema(),
                    "AGENT_SEMANTIC_FACTS", "SUBJECT")) {
                columns.next();
                assertEquals(512, columns.getInt("COLUMN_SIZE"));
            }
            try (var rows = db.createStatement().executeQuery("SELECT subject FROM agent_semantic_facts WHERE id=1")) {
                rows.next();
                assertEquals("existing fact", rows.getString(1));
            }
            String longSubject = "x".repeat(259);
            try (var insert = db.prepareStatement("INSERT INTO agent_semantic_facts(id,subject) VALUES(?,?)")) {
                insert.setLong(1, 2L);
                insert.setString(2, longSubject);
                assertEquals(1, insert.executeUpdate());
            }
        }
    }

    @Test
    void freshDesktopSchemaWithoutTableIsSafeToSkip() throws Exception {
        try (var db = DriverManager.getConnection(
                "jdbc:h2:mem:semantic-subject-empty-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1")) {
            Context context = mock(Context.class);
            when(context.getConnection()).thenReturn(db);

            assertDoesNotThrow(() -> new V40__SemanticFactSubjectCapacity().migrate(context));
        }
    }

    @Test
    void usesMysqlModifyColumnSyntaxForProductionDatabase() throws Exception {
        try (var db = DriverManager.getConnection(
                "jdbc:h2:mem:semantic-subject-mysql-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1")) {
            db.createStatement().execute("CREATE TABLE agent_semantic_facts (id BIGINT PRIMARY KEY, subject VARCHAR(256) NOT NULL)");
            DatabaseMetaData delegate = db.getMetaData();
            DatabaseMetaData mysqlMetadata = (DatabaseMetaData) Proxy.newProxyInstance(
                    DatabaseMetaData.class.getClassLoader(), new Class<?>[]{DatabaseMetaData.class}, (proxy, method, args) -> {
                        if (method.getName().equals("getDatabaseProductName")) return "MySQL";
                        try {
                            return method.invoke(delegate, args);
                        } catch (InvocationTargetException exception) {
                            throw exception.getCause();
                        }
                    });
            Connection mysqlConnection = (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                        if (method.getName().equals("getMetaData")) return mysqlMetadata;
                        try {
                            return method.invoke(db, args);
                        } catch (InvocationTargetException exception) {
                            throw exception.getCause();
                        }
                    });
            Context context = mock(Context.class);
            when(context.getConnection()).thenReturn(mysqlConnection);

            new V40__SemanticFactSubjectCapacity().migrate(context);

            try (var columns = db.getMetaData().getColumns(db.getCatalog(), db.getSchema(),
                    "AGENT_SEMANTIC_FACTS", "SUBJECT")) {
                columns.next();
                assertEquals(512, columns.getInt("COLUMN_SIZE"));
            }
        }
    }
}
