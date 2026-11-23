package com.miniagent.migration;

import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class V32BoundLegacySystemTenantQuotaTest {

    @Test
    void freshDesktopSchemaMayNotHaveTenantsYet() throws Exception {
        try (var connection = DriverManager.getConnection(
                "jdbc:h2:mem:v32_fresh;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "")) {
            Context context = mock(Context.class);
            when(context.getConnection()).thenReturn(connection);

            new V32__BoundLegacySystemTenantQuota().migrate(context);

            assertThat(connection.getMetaData().getTables(null, null, "TENANTS", null).next()).isFalse();
        }
    }

    @Test
    void existingSystemTenantReceivesFiniteQuotaOnlyWhenUnset() throws Exception {
        try (var connection = DriverManager.getConnection(
                "jdbc:h2:mem:v32_existing;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "")) {
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE tenants (slug VARCHAR(64) PRIMARY KEY, daily_token_limit BIGINT)");
                statement.execute("INSERT INTO tenants (slug, daily_token_limit) VALUES ('system', 0)");
                statement.execute("INSERT INTO tenants (slug, daily_token_limit) VALUES ('custom', 17)");
            }
            Context context = mock(Context.class);
            when(context.getConnection()).thenReturn(connection);

            new V32__BoundLegacySystemTenantQuota().migrate(context);

            try (var result = connection.createStatement().executeQuery(
                    "SELECT daily_token_limit FROM tenants WHERE slug='system'")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getLong(1)).isEqualTo(1_000_000L);
            }
            try (var result = connection.createStatement().executeQuery(
                    "SELECT daily_token_limit FROM tenants WHERE slug='custom'")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getLong(1)).isEqualTo(17L);
            }
        }
    }
}
