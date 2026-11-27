package com.miniagent.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Portable H2/MySQL goal queue; the database, rather than JVM timers, owns wakeups. */
public class V44__DurableGoalContinuation extends BaseJavaMigration {
    @Override public void migrate(Context context) throws Exception {
        try (var statement = context.getConnection().createStatement()) {
            statement.execute("""
                CREATE TABLE IF NOT EXISTS agent_pending_goals (
                    goal_id VARCHAR(64) PRIMARY KEY,
                    user_id BIGINT NOT NULL,
                    tenant_id VARCHAR(100) NOT NULL,
                    session_id VARCHAR(100) NOT NULL,
                    task_id BIGINT NOT NULL,
                    execution_id VARCHAR(100) NOT NULL,
                    recovery_round_id VARCHAR(100) NOT NULL,
                    objective TEXT NOT NULL,
                    workspace_root VARCHAR(2000) NOT NULL,
                    goal_state VARCHAR(30) NOT NULL,
                    wakeup_reason VARCHAR(100) NOT NULL,
                    next_wakeup_ms BIGINT NOT NULL,
                    deadline_ms BIGINT NOT NULL,
                    attempts INT NOT NULL,
                    no_progress INT NOT NULL,
                    progress_hash VARCHAR(64) NOT NULL,
                    lease_token VARCHAR(64),
                    lease_until_ms BIGINT NOT NULL,
                    row_version BIGINT NOT NULL,
                    updated_ms BIGINT NOT NULL
                )
                """);
        }
    }
}
