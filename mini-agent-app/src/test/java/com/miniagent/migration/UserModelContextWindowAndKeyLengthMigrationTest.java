package com.miniagent.migration;

import com.miniagent.config.entity.UserModelConfig;
import jakarta.persistence.Column;
import java.sql.Connection;
import java.sql.DriverManager;
import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserModelContextWindowAndKeyLengthMigrationTest {
    @Test void freshDatabaseRemainsForHibernateAndEntityDefinesTheProductionKeyCapacity() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:model-window-empty;MODE=MySQL")) {
            new V27__UserModelContextWindowAndKeyLength().migrate(context(connection));
            try (var tables = connection.getMetaData().getTables(connection.getCatalog(), "PUBLIC", "USER_MODEL_CONFIG", new String[]{"TABLE"})) {
                assertFalse(tables.next());
            }
            assertEquals(1024, UserModelConfig.class.getDeclaredField("customApiKey").getAnnotation(Column.class).length());
            assertEquals("custom_context_window_tokens", UserModelConfig.class.getDeclaredField("customContextWindowTokens")
                    .getAnnotation(Column.class).name());
        }
    }

    @Test void oldDesktopSchemaGainsNullableWindowAndExpandedKeyWithoutChangingExistingData() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:model-window-upgrade;MODE=MySQL")) {
            connection.createStatement().execute("CREATE TABLE user_model_config(id BIGINT PRIMARY KEY, custom_api_key VARCHAR(512))");
            connection.createStatement().execute("INSERT INTO user_model_config VALUES(1,'enc:v1:fixture-ciphertext')");
            var migration = new V27__UserModelContextWindowAndKeyLength();
            migration.migrate(context(connection));
            try (var columns = connection.getMetaData().getColumns(connection.getCatalog(), "PUBLIC", "USER_MODEL_CONFIG", "CUSTOM_API_KEY")) {
                assertTrue(columns.next()); assertEquals(1024, columns.getInt("COLUMN_SIZE"));
            }
            try (var rows = connection.createStatement().executeQuery("SELECT custom_api_key,custom_context_window_tokens FROM user_model_config")) {
                assertTrue(rows.next()); assertEquals("enc:v1:fixture-ciphertext", rows.getString(1));
                assertNull(rows.getObject(2), "An upgrade must not pretend to know a custom model capacity");
            }
            try (var insert = connection.prepareStatement("INSERT INTO user_model_config VALUES(2,?,32000)")) {
                insert.setString(1, "x".repeat(1024)); assertEquals(1, insert.executeUpdate());
            }
            migration.migrate(context(connection));
            try (var rows = connection.createStatement().executeQuery("SELECT custom_context_window_tokens FROM user_model_config WHERE id=2")) {
                assertTrue(rows.next()); assertEquals(32000, rows.getInt(1));
            }
        }
    }

    @Test void alreadyUpgradedOrLargerColumnsAreNotShrunkAndRepeatedMigrationIsSafe() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:model-window-repeat;MODE=MySQL")) {
            connection.createStatement().execute("CREATE TABLE user_model_config(id BIGINT PRIMARY KEY, custom_api_key VARCHAR(2048), custom_context_window_tokens INTEGER)");
            connection.createStatement().execute("INSERT INTO user_model_config VALUES(1,'fixture',64000)");
            var migration = new V27__UserModelContextWindowAndKeyLength();
            migration.migrate(context(connection)); migration.migrate(context(connection));
            try (var columns = connection.getMetaData().getColumns(connection.getCatalog(), "PUBLIC", "USER_MODEL_CONFIG", "CUSTOM_API_KEY")) {
                assertTrue(columns.next()); assertEquals(2048, columns.getInt("COLUMN_SIZE"));
            }
            try (var rows = connection.createStatement().executeQuery("SELECT custom_context_window_tokens FROM user_model_config")) {
                assertTrue(rows.next()); assertEquals(64000, rows.getInt(1));
            }
        }
    }

    @Test void schemaWithProduction1024CharacterKeyRetainsItAndOtherSchemasAreUntouched() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:model-window-production;MODE=MySQL")) {
            connection.createStatement().execute("CREATE TABLE user_model_config(id BIGINT PRIMARY KEY, custom_api_key VARCHAR(1024))");
            connection.createStatement().execute("CREATE SCHEMA archive");
            connection.createStatement().execute("CREATE TABLE archive.user_model_config(id BIGINT PRIMARY KEY, custom_api_key VARCHAR(512))");
            new V27__UserModelContextWindowAndKeyLength().migrate(context(connection));
            try (var columns = connection.getMetaData().getColumns(connection.getCatalog(), "ARCHIVE", "USER_MODEL_CONFIG", "CUSTOM_CONTEXT_WINDOW_TOKENS")) {
                assertFalse(columns.next());
            }
            try (var columns = connection.getMetaData().getColumns(connection.getCatalog(), "PUBLIC", "USER_MODEL_CONFIG", "CUSTOM_API_KEY")) {
                assertTrue(columns.next()); assertEquals(1024, columns.getInt("COLUMN_SIZE"));
            }
        }
    }

    private static Context context(Connection connection) {
        Context context = mock(Context.class); when(context.getConnection()).thenReturn(connection); return context;
    }
}
