package com.miniagent.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 删除本地额度表：token 额度改为按会员套餐在账号服务计算，本地不再自己累计。
 *
 * <p>删两张：{@code user_daily_usage}（用户日用量）与 {@code tenant_daily_usage}（租户日用量）。
 * 对应的实体、仓储、{@code DbTenantTokenQuota} 已在同一批改动里移除；保留空表只会让后来的人
 * 以为还有一层本地额度在生效。</p>
 *
 * <p><b>数据不迁移。</b> 这些表里只有按天累计的 token 数字，没有用户会读到的东西，
 * 账号服务的 {@code user_llm_call_usage} 已经有逐次调用的明细。迁移一份旧的累加值过去，
 * 会和云端已有数据重复计。</p>
 *
 * <p>先删子表再删父表方向的约束：{@code user_daily_usage} 上有指向 {@code users} 的外键，
 * MySQL 删除被引用方的表时会报错，所以顺序是「先删带外键的表」。两个表之间没有互相引用。</p>
 */
public class V41__DropLocalTokenQuotaTables extends BaseJavaMigration {

    private static final List<String> TABLES = List.of("user_daily_usage", "tenant_daily_usage");

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        // 逐表判断存在性而不是直接 DROP IF EXISTS：H2 与 MySQL 对 IF EXISTS 的兼容性不同，
        // 而且这里需要记录「实际删了哪些」，方便在只有一张表的库上看清发生了什么。
        List<String> existing = existingTables(connection);
        try (Statement statement = connection.createStatement()) {
            for (String table : TABLES) {
                if (existing.contains(table)) {
                    statement.execute("DROP TABLE " + table);
                }
            }
        }
    }

    private static List<String> existingTables(Connection connection) throws Exception {
        List<String> found = new ArrayList<>();
        DatabaseMetaData metadata = connection.getMetaData();
        String catalog = connection.getCatalog();
        String schema = connection.getSchema();
        for (String table : TABLES) {
            if (exists(metadata, catalog, schema, table)) found.add(table);
        }
        return found;
    }

    private static boolean exists(DatabaseMetaData metadata, String catalog, String schema, String table)
            throws Exception {
        for (String candidate : new String[]{table, table.toUpperCase(Locale.ROOT)}) {
            try (ResultSet tables = metadata.getTables(catalog, schema, candidate, new String[]{"TABLE"})) {
                if (tables.next()) return true;
            }
        }
        return false;
    }
}
