package com.miniagent.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;

/**
 * V9：孤儿表清理 + 复合索引回填（H2 / MySQL 双库通用）。
 *
 * <p><b>为什么是 Java 迁移而不是 SQL</b>：桌面档用的是 H2（{@code MODE=MySQL}），
 * 而"建索引前先判断存在性"的 SQL 守卫只能靠 {@code SET @var := ... + PREPARE} 这套
 * MySQL 用户变量语法（MySQL 8 不支持 {@code ADD COLUMN IF NOT EXISTS}，实测
 * ERROR 1064）。{@code SET @x := 1} 在 H2 上直接语法报错（实测
 * {@code Syntax error in SQL statement "SET @has_col [*]:= 1"}），{@code MODE=MySQL}
 * 也救不了。Java 侧用 JDBC {@link DatabaseMetaData} 判存在性，两个库一套代码，
 * 不依赖任何方言扩展。
 *
 * <p><b>为什么索引存在性必须显式守卫</b>：{@code CREATE INDEX} 不是幂等语句，
 * 撞上同名索引在 MySQL 报 ERROR 1061、在 H2 报 IndexAlreadyExists。而本迁移会在
 * 两种状态下各跑一次：老装机上索引缺失（要建）、MySQL 上索引已由 V5 建过（要跳过）。
 * 守卫之外再兜一层"建完复查"：万一存在性判断看走眼（同名索引列定义不同之类），
 * 也不能让整条迁移链挂掉 —— MySQL 的 DDL 不在事务里，失败会留下 success=0 的
 * 历史记录把链卡死（V5 事故的机制）。
 *
 * <p><b>幂等性</b>：DROP TABLE IF EXISTS 本身就是幂等的；索引两次守卫都跳过。
 * 连跑两次无副作用，实测口径。
 *
 * <p><b>执行范围</b>：prod（MySQL）与 desktop（H2）共用同一条迁移链。MySQL 上
 * 这两条意图早已分别由 V7 / V5 完成，本迁移是纯 no-op（6 张表已不在、索引已在），
 * 留在链上是为了让两边的历史表版本号始终对齐。
 */
public class V9__BackfillIndexAndDropIntentTables extends BaseJavaMigration {

    /** V5 里定死的复合索引名。列顺序服务「某用户未删除的会话列表按时间倒序」，不能调换。 */
    private static final String INDEX_NAME = "idx_chat_task_user_deleted_session_created";

    /** V7 已删过一轮的孤儿表（意图分类层下线后的遗留）。按引用倒序：先子表后父表。 */
    private static final List<String> ORPHAN_TABLES = List.of(
            "intent_rule_proposal",
            "intent_rule_feedback",
            "intent_rule_hit_log",
            "intent_tool_profile",
            "intent_rule",
            "intent_rule_set");

    /**
     * 版本号来自类名约定 {@code V9__...}（BaseJavaMigration 的 getVersion() 默认返回
     * null，Flyway 就按类名解析）。Flyway 10 里 getVersion() 的返回类型是
     * {@code MigrationVersion} 而不是 String，显式覆盖只会引入额外的解析失败面。
     */
    @Override
    public String getDescription() {
        return "backfill chat_tasks index and drop orphaned intent_rule tables";
    }

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();

        for (String table : ORPHAN_TABLES) {
            dropIfExists(connection, table);
        }

        String chatTasks = findTableName(connection, "chat_tasks");
        if (chatTasks != null && findIndexName(connection, chatTasks, INDEX_NAME) == null) {
            try (Statement statement = connection.createStatement()) {
                // 列名与索引名刻意都不加引号：H2 / MySQL 会各自归一化成自己的大小写，
                // 与两边建表/建索引的历史写法（Hibernate ddl-auto、V5 的 ALTER TABLE
                // ADD KEY）保持一致。加引号反而会造出大小写不同的同名索引。
                statement.execute("CREATE INDEX " + INDEX_NAME + " ON " + chatTasks
                        + " (user_id, deleted, session_id, created_at)");
            }
            // 建完复查：存在性判断失误时（例如同名索引列定义不同），就当意图已满足，
            // 不让整条迁移链挂掉。真缺列会在这句之前就抛异常，不会走到这里。
            if (findIndexName(connection, chatTasks, INDEX_NAME) == null) {
                throw new IllegalStateException(
                        "CREATE INDEX " + INDEX_NAME + " 执行后仍找不到该索引，模式可能已漂移，需人工核对");
            }
        }
    }

    private void dropIfExists(Connection connection, String logicalName) throws Exception {
        String actual = findTableName(connection, logicalName);
        if (actual == null) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS " + actual);
        }
    }

    /**
     * 按名字在当前库里找表（大小写不敏感），返回库里实际存储的名字；找不到返回 null。
     * 不能直接拿逻辑名拼 SQL：H2 会把未加引号的标识符统一成大写，而 Flyway 自己的
     * 历史表是带引号建的小写表名 —— 大小写必须以库里存的为准。
     */
    private String findTableName(Connection connection, String logicalName) throws Exception {
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet rs = metaData.getTables(
                connection.getCatalog(), connection.getSchema(), "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                String schema = rs.getString("TABLE_SCHEM");
                if (schema != null && schema.toLowerCase(Locale.ROOT).equals("information_schema")) {
                    continue;
                }
                String name = rs.getString("TABLE_NAME");
                if (name.equalsIgnoreCase(logicalName)) {
                    return name;
                }
            }
        }
        return null;
    }

    /** 按名字在指定表上找索引（大小写不敏感），返回库里实际存储的索引名；找不到返回 null。 */
    private String findIndexName(Connection connection, String tableName, String logicalName) throws Exception {
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet rs = metaData.getIndexInfo(
                connection.getCatalog(), connection.getSchema(), tableName, false, false)) {
            while (rs.next()) {
                String name = rs.getString("INDEX_NAME");
                if (name != null && name.equalsIgnoreCase(logicalName)) {
                    return name;
                }
            }
        }
        return null;
    }
}
