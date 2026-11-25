package com.miniagent.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Locale;

/**
 * 本地用户表增加云端服务令牌两列。
 *
 * <p>令牌由账号服务在登录时签发（{@code atk_...}），本机只负责保存并在调用会员接口时带上。
 * 它绑定了 userId，所以能拦住「拿别人的 id 写用量」；全局 internal-key 做不到这一点。</p>
 *
 * <p>用 Java 迁移而不是 SQL：H2 与 MySQL 对 {@code ADD COLUMN IF NOT EXISTS} 的支持不同，
 * 而这个脚本会在两种库上都跑（桌面 H2、生产 MySQL）。逐列判断是唯一两边都稳的写法。</p>
 *
 * <p>两列都可空：老版本服务端不返回令牌，此时保持 null 表示「还没有」，
 * 而不是写入空串——空串会让「有没有令牌」这个判断失效。</p>
 */
public class V42__CloudAgentTokenColumns extends BaseJavaMigration {

    private static final String TABLE = "users";

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        DatabaseMetaData metadata = connection.getMetaData();
        String catalog = connection.getCatalog();
        String schema = connection.getSchema();

        try (Statement statement = connection.createStatement()) {
            addColumnIfMissing(metadata, catalog, schema, connection, statement,
                    "cloud_agent_token", "VARCHAR(128) NULL");
            addColumnIfMissing(metadata, catalog, schema, connection, statement,
                    "cloud_agent_token_expires_at", "TIMESTAMP NULL");
        }
    }

    private static void addColumnIfMissing(DatabaseMetaData metadata, String catalog, String schema,
                                           Connection connection, Statement statement,
                                           String column, String definition) throws Exception {
        if (columnExists(metadata, catalog, schema, column)) return;
        statement.execute("ALTER TABLE " + TABLE + " ADD COLUMN " + column + " " + definition);
    }

    private static boolean columnExists(DatabaseMetaData metadata, String catalog, String schema,
                                        String column) throws Exception {
        for (String candidate : new String[]{column, column.toUpperCase(Locale.ROOT)}) {
            try (ResultSet columns = metadata.getColumns(catalog, schema, "%", candidate)) {
                while (columns.next()) {
                    if (TABLE.equalsIgnoreCase(columns.getString("TABLE_NAME"))) return true;
                }
            }
        }
        return false;
    }
}
