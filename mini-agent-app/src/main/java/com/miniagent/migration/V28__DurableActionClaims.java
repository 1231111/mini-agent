package com.miniagent.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Shared execution ownership. Works for both MySQL and an empty desktop H2 database. */
public class V28__DurableActionClaims extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        try (var statement = context.getConnection().createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS agent_action_receipt (
                      key_digest VARCHAR(64) PRIMARY KEY,
                      tool_name VARCHAR(128) NOT NULL,
                      arguments_hash VARCHAR(64) NOT NULL,
                      execution_status VARCHAR(16) NOT NULL,
                      attempt INTEGER NOT NULL,
                      timestamp_ms BIGINT NOT NULL,
                      error_code VARCHAR(64) NOT NULL,
                      diagnostic_message VARCHAR(1000) NOT NULL,
                      result_digest VARCHAR(64) NOT NULL
                    )
                    """);
        }
    }
}
