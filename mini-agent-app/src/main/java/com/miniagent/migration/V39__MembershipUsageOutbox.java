package com.miniagent.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Durable, idempotent delivery of model usage from agent to account service. */
public class V39__MembershipUsageOutbox extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        var connection = context.getConnection();
        if (tableExists(connection, "membership_usage_outbox")) return;
        try (var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE membership_usage_outbox ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT,"
                    + "event_id VARCHAR(36) NOT NULL,"
                    + "cloud_user_id BIGINT NOT NULL,"
                    + "input_tokens BIGINT NOT NULL,"
                    + "output_tokens BIGINT NOT NULL,"
                    + "status VARCHAR(16) NOT NULL,"
                    + "attempts INT NOT NULL DEFAULT 0,"
                    + "next_attempt_at DATETIME(6) NOT NULL,"
                    + "lease_until DATETIME(6) NULL,"
                    + "claim_token VARCHAR(36) NULL,"
                    + "last_error VARCHAR(1000) NULL,"
                    + "created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),"
                    + "updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),"
                    + "PRIMARY KEY (id),"
                    + "CONSTRAINT ux_muo_event UNIQUE (event_id))");
            statement.execute("CREATE INDEX idx_muo_due ON membership_usage_outbox (status,next_attempt_at)");
        }
    }

    private static boolean tableExists(java.sql.Connection connection, String table)
            throws java.sql.SQLException {
        try (var tables = connection.getMetaData().getTables(connection.getCatalog(), null, "%", null)) {
            while (tables.next()) {
                if (table.equalsIgnoreCase(tables.getString("TABLE_NAME"))) return true;
            }
        }
        return false;
    }
}
