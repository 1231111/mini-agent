package com.miniagent.config.flyway;

import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 桌面档（H2）专用的 Flyway 执行策略。
 *
 * <p><b>它解决的问题</b>：Flyway 的 {@code baseline-on-migrate} 只对
 * 「库非空且没有历史表」的场景生效；<b>空库</b>上 migrate 会从最小版本起把迁移全跑一遍
 * （实测 Flyway 10.20.1 + H2 2.3.232：配置 baseline-version=8 后，空库仍执行了 V8+V9）。
 * 桌面档的新装机恰好就是空库，而 V1~V8 是 MySQL 方言脚本（V5 的
 * {@code SET @var := / PREPARE} 用户变量在 H2 上直接语法报错）—— 空库直接 migrate 必炸。
 *
 * <p><b>策略</b>：没有历史表就先显式 {@code baseline()} 再 {@code migrate()}。
 * 实测空库上允许 baseline（"Successfully baselined schema with version: 8"），于是
 * 新装机（空库）与老装机（有表、无历史表）走同一条路：打 baseline=8，只跑 V9 起的增量。
 * 库结构的基线由 {@code ddl-auto: update} 从实体反推，Flyway 只管增量。
 *
 * <p><b>为什么不给 prod 挂这个 bean</b>：prod 面对的是 MySQL，全新部署的空库必须
 * 从 V1 起跑全量建表（prod 是 {@code ddl-auto: validate}，Hibernate 不建表）。
 * baseline=8 对那个场景是错的，所以本类钉死 {@code @Profile("desktop")}。
 *
 * <p><b>失败行自愈</b>：历史表里 {@code success=0} 的记录会让 Flyway 此后每次启动都
 * 拒绝执行（"Detected failed migration"），整个客户端瘫掉且没有修复入口 —— V5 事故
 * 在 MySQL 上就是这个机制。H2 的 DDL 在事务里（失败整体回滚、不留半截结构），
 * 所以删掉失败行重跑是安全的；MySQL 的 DDL 不在事务里（失败可能留下半截结构），
 * prod 不做这种自动修复，那里的失败行必须人工核对后 repair。
 */
@Configuration
@Profile("desktop")
public class DesktopFlywayConfig {

    @Bean
    public FlywayMigrationStrategy desktopFlywayMigrationStrategy(DataSource dataSource) {
        return flyway -> {
            String historyTable = flyway.getConfiguration().getTable();
            try (Connection connection = dataSource.getConnection()) {
                clearFailedRows(connection, historyTable);
                if (findTableName(connection, historyTable) == null) {
                    // 新装机（空库）、老装机（有业务表、无历史表）都落在这一支。
                    // baseline() 只写一条 "<< Flyway Baseline >>"（version=8）到历史表，
                    // 不碰业务表；V1~V8 从此永远跳过。
                    flyway.baseline();
                }
            } catch (SQLException e) {
                // FlywayMigrationStrategy.migrate() 不允许受检异常穿透，统一转成运行时异常。
                // 失败发生在 baseline 之前：库结构没动过，重试无副作用。
                throw new IllegalStateException("检查/修复 Flyway 历史表失败", e);
            }
            flyway.migrate();
        };
    }

    /** 删掉历史表里执行失败的行（success=0），让被卡死的迁移链能重跑。见类注释的安全性论证。 */
    private void clearFailedRows(Connection connection, String historyTable) throws SQLException {
        String table = findTableName(connection, historyTable);
        if (table == null) {
            return;
        }
        String successColumn = findColumnName(connection, table, "success");
        if (successColumn == null) {
            // 历史表结构不是 Flyway 标准结构，不碰它。
            return;
        }
        String quote = connection.getMetaData().getIdentifierQuoteString();
        String sql = "DELETE FROM " + quote + table + quote
                + " WHERE " + quote + successColumn + quote + " = FALSE";
        try (Statement statement = connection.createStatement()) {
            int removed = statement.executeUpdate(sql);
            if (removed > 0) {
                System.out.println("[MiniAgent] Flyway 历史表清理了 " + removed
                        + " 条失败记录（success=0），迁移将重跑");
            }
        }
    }

    /** 按名字在当前库里找表（大小写不敏感），返回库里实际存储的名字；找不到返回 null。 */
    private String findTableName(Connection connection, String logicalName) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet rs = metaData.getTables(
                connection.getCatalog(), connection.getSchema(), "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                String schema = rs.getString("TABLE_SCHEM");
                if (schema != null && schema.equalsIgnoreCase("information_schema")) {
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

    /** 按名字在指定表上找列（大小写不敏感），返回库里实际存储的列名；找不到返回 null。 */
    private String findColumnName(Connection connection, String tableName, String logicalName) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet rs = metaData.getColumns(
                connection.getCatalog(), connection.getSchema(), tableName, "%")) {
            while (rs.next()) {
                String name = rs.getString("COLUMN_NAME");
                if (name != null && name.equalsIgnoreCase(logicalName)) {
                    return name;
                }
            }
        }
        return null;
    }
}
