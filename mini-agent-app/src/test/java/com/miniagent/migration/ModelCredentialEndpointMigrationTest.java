package com.miniagent.migration;

import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ModelCredentialEndpointMigrationTest {
    @Test
    void freshDatabaseSkipsMigrationBeforeHibernateCreatesTheTable() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:model-endpoint-empty;MODE=MySQL")) {
            Context context = context(connection);

            assertDoesNotThrow(() -> new V26__ModelCredentialEndpoint().migrate(context));

            try (var tables = connection.getMetaData().getTables(connection.getCatalog(), "PUBLIC",
                    "USER_MODEL_CONFIG", new String[]{"TABLE"})) {
                assertFalse(tables.next(), "Migration must not create a partial configuration table");
            }
        }
    }

    @Test
    void oldTableGainsNullable512CharacterEndpointAndKeepsLegacyKeyUnbound() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:model-endpoint-upgrade;MODE=MySQL")) {
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE user_model_config(id BIGINT PRIMARY KEY, custom_api_key VARCHAR(512))");
                statement.execute("INSERT INTO user_model_config VALUES(1, 'enc:v1:legacy-ciphertext')");
            }

            new V26__ModelCredentialEndpoint().migrate(context(connection));

            try (var columns = connection.getMetaData().getColumns(connection.getCatalog(), "PUBLIC",
                    "USER_MODEL_CONFIG", "CUSTOM_API_KEY_BASE_URL")) {
                assertTrue(columns.next());
                assertEquals(512, columns.getInt("COLUMN_SIZE"));
                assertEquals(DatabaseMetaData.columnNullable, columns.getInt("NULLABLE"));
            }
            try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                    "SELECT custom_api_key, custom_api_key_base_url FROM user_model_config WHERE id=1")) {
                assertTrue(rows.next());
                assertEquals("enc:v1:legacy-ciphertext", rows.getString(1));
                assertNull(rows.getString(2), "Migration cannot guess the destination of an existing credential");
            }
        }
    }

    @Test
    void repeatedMigrationPreservesBothExistingKeysAndConfirmedEndpointBindings() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:model-endpoint-repeat;MODE=MySQL")) {
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE user_model_config(id BIGINT PRIMARY KEY, custom_api_key VARCHAR(512))");
                statement.execute("INSERT INTO user_model_config VALUES(1, 'enc:v1:bound-ciphertext')");
            }
            Context context = context(connection);
            var migration = new V26__ModelCredentialEndpoint();
            migration.migrate(context);
            try (var statement = connection.createStatement()) {
                statement.execute("UPDATE user_model_config SET custom_api_key_base_url='https://fixture.example/v1' WHERE id=1");
            }

            assertDoesNotThrow(() -> migration.migrate(context));
            assertDoesNotThrow(() -> migration.migrate(context));

            try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                    "SELECT custom_api_key, custom_api_key_base_url FROM user_model_config WHERE id=1")) {
                assertTrue(rows.next());
                assertEquals("enc:v1:bound-ciphertext", rows.getString(1));
                assertEquals("https://fixture.example/v1", rows.getString(2));
                assertFalse(rows.next());
            }
        }
    }

    @Test
    void tableInAnotherSchemaDoesNotMakeAnEmptyCurrentSchemaFail() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:model-endpoint-schema;MODE=MySQL")) {
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE SCHEMA archive");
                statement.execute("CREATE TABLE archive.user_model_config(id BIGINT PRIMARY KEY, custom_api_key VARCHAR(512))");
            }

            assertDoesNotThrow(() -> new V26__ModelCredentialEndpoint().migrate(context(connection)));

            try (var columns = connection.getMetaData().getColumns(connection.getCatalog(), "ARCHIVE",
                    "USER_MODEL_CONFIG", "CUSTOM_API_KEY_BASE_URL")) {
                assertFalse(columns.next(), "Migration must not alter another schema's configuration table");
            }
        }
    }

    private static Context context(Connection connection) {
        Context context = mock(Context.class);
        when(context.getConnection()).thenReturn(connection);
        return context;
    }
}
