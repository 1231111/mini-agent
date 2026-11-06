package com.miniagent.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Locale;

/** V21：节点终态 / 用户反馈投递给 memory-service 的可靠队列。取代 v1 的三条进化 outbox。 */
public class V21__MemoryOutcomeOutbox extends BaseJavaMigration {
    private static final String TABLE = "agent_memory_outcome_outbox";

    @Override
    public String getDescription() {
        return "add durable outcome outbox for memory-service evolution events";
    }

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        if (tableExists(connection, TABLE)) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE " + TABLE + " ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT,"
                    + "event_id VARCHAR(128) NOT NULL,"
                    + "tenant_id VARCHAR(64) NOT NULL,"
                    + "payload_json LONGTEXT NOT NULL,"
                    + "status VARCHAR(16) NOT NULL,"
                    + "attempts INT NOT NULL DEFAULT 0,"
                    + "next_attempt_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),"
                    + "lease_until DATETIME(6),"
                    + "claim_token VARCHAR(36),"
                    + "last_error VARCHAR(1000),"
                    + "created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),"
                    + "updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),"
                    + "PRIMARY KEY (id))");
            statement.execute("CREATE UNIQUE INDEX ux_amoo_event ON " + TABLE + " (event_id)");
            statement.execute("CREATE INDEX idx_amoo_due ON " + TABLE
                    + " (status, next_attempt_at)");
        }
    }

    private boolean tableExists(Connection connection, String target) throws Exception {
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet rs = metadata.getTables(connection.getCatalog(), null, "%",
                new String[]{"TABLE"})) {
            while (rs.next()) {
                String schema = rs.getString("TABLE_SCHEM");
                String table = rs.getString("TABLE_NAME");
                if ((schema == null || !schema.toLowerCase(Locale.ROOT).equals("information_schema"))
                        && target.equalsIgnoreCase(table)) {
                    return true;
                }
            }
        }
        return false;
    }
}
