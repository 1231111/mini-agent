package com.miniagent.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Durable local side of user-subject erasure. The row is a write tombstone. */
public class V35__SubjectErasureState extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        try (var statement = context.getConnection().createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS memory_subject_erasure ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT,"
                    + "tenant_id VARCHAR(64) NOT NULL,"
                    + "user_id BIGINT NOT NULL,"
                    + "subject_hash VARCHAR(64) NOT NULL,"
                    + "status VARCHAR(24) NOT NULL,"
                    + "local_completed_at DATETIME(6) NULL,"
                    + "remote_completed_at DATETIME(6) NULL,"
                    + "last_error VARCHAR(1000) NULL,"
                    + "updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),"
                    + "PRIMARY KEY (id),"
                    + "UNIQUE KEY ux_memory_subject_erasure (tenant_id,user_id),"
                    + "KEY idx_memory_subject_erasure_hash (tenant_id,subject_hash)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
        }
    }
}
