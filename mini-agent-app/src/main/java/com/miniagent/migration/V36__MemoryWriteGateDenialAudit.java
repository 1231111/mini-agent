package com.miniagent.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Durable, bounded audit trail for memory write-gate decisions. */
public class V36__MemoryWriteGateDenialAudit extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        try (var statement = context.getConnection().createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS agent_memory_write_gate_denials ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT,"
                    + "tenant_id VARCHAR(64) NOT NULL,"
                    + "rule_code VARCHAR(32) NOT NULL,"
                    + "reason VARCHAR(500) NULL,"
                    + "created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_amwgd_tenant_date (tenant_id, created_at),"
                    + "KEY idx_amwgd_rule_date (rule_code, created_at)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
        }
    }
}
