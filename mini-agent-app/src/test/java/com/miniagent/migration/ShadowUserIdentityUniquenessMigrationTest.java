package com.miniagent.migration;

import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ShadowUserIdentityUniquenessMigrationTest {
    @Test void emptyDesktopDatabaseDoesNotMistakeTheH2SystemUsersTableForApplicationData() throws Exception {
        try (Connection db = database()) {
            migrate(db);
            assertFalse(V23__CloudMemoryAccessToken.tableExists(db));
        }
    }

    @Test void existingDesktopDatabaseGainsAnIdempotentUniqueMappingAndAllowsLocalAccounts() throws Exception {
        try (Connection db = database(); var sql = db.createStatement()) {
            sql.execute("CREATE TABLE users(id BIGINT PRIMARY KEY, external_id VARCHAR(128))");
            sql.execute("INSERT INTO users VALUES (1,NULL),(2,NULL),(3,'cloud-2')");
            migrate(db);
            migrate(db);
            assertThrows(SQLException.class, () -> sql.execute("INSERT INTO users VALUES (4,'cloud-2')"));
            sql.execute("INSERT INTO users VALUES (5,NULL)");
            assertEquals(4, count(db));
        }
    }

    @Test void oldDatabaseWithoutExternalIdColumnCanBeUpgradedBeforeHibernate() throws Exception {
        try (Connection db = database(); var sql = db.createStatement()) {
            sql.execute("CREATE TABLE users(id BIGINT PRIMARY KEY)");
            sql.execute("INSERT INTO users VALUES (1)");
            migrate(db);
            sql.execute("UPDATE users SET external_id='cloud-2' WHERE id=1");
            assertThrows(SQLException.class, () -> sql.execute("INSERT INTO users VALUES (2,'cloud-2')"));
        }
    }

    @Test void existingEquivalentUniqueIndexDoesNotCreateADuplicateIndex() throws Exception {
        try (Connection db = database(); var sql = db.createStatement()) {
            sql.execute("CREATE TABLE users(id BIGINT PRIMARY KEY, external_id VARCHAR(128) UNIQUE)");
            migrate(db);
            migrate(db);
            sql.execute("INSERT INTO users VALUES (1,'cloud-2')");
            assertThrows(SQLException.class, () -> sql.execute("INSERT INTO users VALUES (2,'cloud-2')"));
        }
    }

    @Test void existingDuplicateIdentitiesAreRejectedWithoutDeletingOrCombiningUsers() throws Exception {
        try (Connection db = database(); var sql = db.createStatement()) {
            sql.execute("CREATE TABLE users(id BIGINT PRIMARY KEY, external_id VARCHAR(128))");
            sql.execute("INSERT INTO users VALUES (1,'same-cloud-id'),(2,'same-cloud-id')");
            SQLException failure = assertThrows(SQLException.class, () -> migrate(db));
            assertTrue(failure.getMessage().contains("duplicate external identities"));
            assertFalse(failure.getMessage().contains("same-cloud-id"), "Do not expose identity values in startup errors");
            assertEquals(2, count(db));
        }
    }

    @Test void compositeUniqueIndexIsInsufficientToProtectOneExternalIdentity() throws Exception {
        try (Connection db = database(); var sql = db.createStatement()) {
            sql.execute("CREATE TABLE users(id BIGINT PRIMARY KEY, username VARCHAR(50), external_id VARCHAR(128), "
                    + "UNIQUE(username,external_id))");
            sql.execute("INSERT INTO users VALUES (1,'first','cloud-2')");
            migrate(db);
            assertThrows(SQLException.class, () -> sql.execute("INSERT INTO users VALUES (2,'second','cloud-2')"));
            assertEquals(1, count(db));
        }
    }

    private static Connection database() throws Exception {
        return DriverManager.getConnection("jdbc:h2:mem:shadow-identity-" + UUID.randomUUID() + ";MODE=MySQL");
    }
    private static void migrate(Connection db) throws Exception {
        Context context = mock(Context.class);
        when(context.getConnection()).thenReturn(db);
        new V30__ShadowUserIdentityUniqueness().migrate(context);
    }
    private static int count(Connection db) throws Exception {
        try (var sql = db.createStatement(); var rows = sql.executeQuery("SELECT COUNT(*) FROM users")) {
            rows.next();
            return rows.getInt(1);
        }
    }
}
