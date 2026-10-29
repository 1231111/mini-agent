package com.miniagent.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Locale;

/**
 * V17：SOP 投影到独立记忆服务的可靠投递队列。
 *
 * <p><b>为什么必须有这张表</b>：remote 模式下，记忆服务只认它自己那份 Procedure 投影。
 * 执行侧写了一条 SOP、投影却没送到，之后所有针对该版本的节点结算都会返回
 * {@code STALE_PROCEDURE} —— 统计永远不动，而且任务照样“成功”。这类丢失如果只靠
 * 一次同步 HTTP 调用，网络抖一下就会发生，且事后无从追查。</p>
 *
 * <p><b>为什么存内容快照而不是只存 procedureId</b>：投递必须按版本推进。占用同一行的是
 * 可变的状态：本地升到 v3 时，v2 的内容已经被覆盖。只存 id 的队列在积压后只能投递最新版，
 * 中间版本凭空消失，服务端看到的版本序列因此出现空洞。快照把「要投的这一版」钉住。</p>
 *
 * <p><b>为什么不与 {@code agent_procedures} 合并</b>：outbox 的生命周期是“投递成功即结束”，
 * 而 SOP 行要长期保留。混在一起会让清理逻辑碰到正表。</p>
 */
public class V17__ProcedureDefinitionOutbox extends BaseJavaMigration {

    private static final String TABLE = "agent_procedure_definition_outbox";

    @Override
    public String getDescription() {
        return "add outbox for procedure definition projection to memory service";
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
                    + "tenant_id VARCHAR(64) NOT NULL,"
                    + "user_id BIGINT NOT NULL,"
                    + "project_id VARCHAR(128),"
                    + "procedure_id BIGINT NOT NULL,"
                    + "procedure_version INT NOT NULL,"
                    + "scope_type VARCHAR(32) NOT NULL,"
                    + "scope_id VARCHAR(128) NOT NULL,"
                    + "name VARCHAR(256) NOT NULL,"
                    + "description TEXT,"
                    + "preconditions_json TEXT,"
                    + "steps_json LONGTEXT NOT NULL,"
                    + "success_conditions_json TEXT,"
                    + "importance DOUBLE,"
                    + "procedure_status VARCHAR(32) NOT NULL,"
                    + "status VARCHAR(16) NOT NULL,"
                    + "attempts INT NOT NULL DEFAULT 0,"
                    + "next_attempt_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                    + "last_error VARCHAR(1000),"
                    + "created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                    + "updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                    + "PRIMARY KEY (id)"
                    + ")");
        }
        // 同一行同一版本只该有一条待投递记录：重复的投影没有语义，还会拖慢队列。
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE UNIQUE INDEX ux_apdo_version ON " + TABLE
                    + " (tenant_id, procedure_id, procedure_version)");
            statement.execute("CREATE INDEX idx_apdo_pending ON " + TABLE
                    + " (status, next_attempt_at)");
        }
    }

    private boolean tableExists(Connection connection, String name) throws Exception {
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet tables = metadata.getTables(connection.getCatalog(), null, "%",
                new String[]{"TABLE"})) {
            while (tables.next()) {
                String schema = tables.getString("TABLE_SCHEM");
                String table = tables.getString("TABLE_NAME");
                if ((schema == null || !schema.toLowerCase(Locale.ROOT).equals("information_schema"))
                        && table != null && table.equalsIgnoreCase(name)) {
                    return true;
                }
            }
        }
        return false;
    }
}
