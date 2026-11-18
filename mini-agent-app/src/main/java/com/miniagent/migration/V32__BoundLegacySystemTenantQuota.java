package com.miniagent.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Give the shared local tenant a finite baseline budget instead of unlimited usage. */
public class V32__BoundLegacySystemTenantQuota extends BaseJavaMigration {
    private static final long DEFAULT_DAILY_TOKEN_LIMIT = 1_000_000L;

    @Override
    public void migrate(Context context) throws Exception {
        try (var statement = context.getConnection().prepareStatement(
                "UPDATE tenants SET daily_token_limit=? WHERE slug=? AND daily_token_limit<=0")) {
            statement.setLong(1, DEFAULT_DAILY_TOKEN_LIMIT);
            statement.setString(2, "system");
            statement.executeUpdate();
        }
    }
}
