package com.miniagent.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Durable, owner-scoped episode-to-procedure proposal delivery. */
public class V43__FailureLearningOutbox extends BaseJavaMigration {
    @Override public void migrate(Context context) throws Exception {
        var connection = context.getConnection();
        try (var tables = connection.getMetaData().getTables(connection.getCatalog(), null, "%", new String[]{"TABLE"})) {
            while (tables.next()) {
                if ("agent_failure_learning_outbox".equalsIgnoreCase(tables.getString("TABLE_NAME"))) return;
            }
        }
        try (var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE agent_failure_learning_outbox ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,episode_id BIGINT NOT NULL,source_hash VARCHAR(64) NOT NULL,"
                    + "tenant_id VARCHAR(64) NOT NULL,user_id BIGINT NOT NULL,status VARCHAR(16) NOT NULL,attempts INT NOT NULL DEFAULT 0,"
                    + "next_attempt_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),lease_until DATETIME(6),claim_token VARCHAR(36),"
                    + "last_error VARCHAR(1000),created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),"
                    + "updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6))");
            statement.execute("CREATE UNIQUE INDEX ux_aflo_source ON agent_failure_learning_outbox (episode_id,source_hash)");
            statement.execute("CREATE INDEX idx_aflo_due ON agent_failure_learning_outbox (status,next_attempt_at)");
        }
    }
}
