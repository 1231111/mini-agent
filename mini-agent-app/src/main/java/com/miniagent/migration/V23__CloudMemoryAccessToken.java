package com.miniagent.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;

/** V23: persist the Account tenant mapping and memory access token for shadow users. */
public class V23__CloudMemoryAccessToken extends BaseJavaMigration {
    private static final String TABLE = "users";

    @Override
    public String getDescription() {
        return "store cloud memory access identity";
    }

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        if (!tableExists(connection)) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            if (!columnExists(connection, "cloud_tenant_id")) {
                statement.execute("ALTER TABLE " + TABLE + " ADD COLUMN cloud_tenant_id VARCHAR(128)");
            }
            if (!columnExists(connection, "memory_access_token")) {
                statement.execute("ALTER TABLE " + TABLE + " ADD COLUMN memory_access_token VARCHAR(4096)");
            }
            if (!columnExists(connection, "memory_refresh_credential")) {
                statement.execute("ALTER TABLE " + TABLE + " ADD COLUMN memory_refresh_credential VARCHAR(128)");
            }
        }
    }

    /**
     * 判表存在时必须同时判 schema。
     *
     * <p>H2 的 {@code INFORMATION_SCHEMA} 里自带一张叫 {@code USERS} 的系统表。
     * 原来 {@code getTables(catalog, null, "%", ...)} 的 schemaPattern 是 {@code null}
     * （= 不过滤 schema），于是全新安装的空库上，这张系统表会被误认为业务表 users，
     * {@code tableExists} 返回 true，紧接着执行
     * {@code ALTER TABLE users ADD COLUMN cloud_tenant_id ...} —— 而 PUBLIC 下
     * 根本还没有 users 表（桌面档靠 {@code ddl-auto: update} 建表，发生在 Flyway
     * 之后），直接报 {@code Table "USERS" not found}，新装机启动失败。
     * 2026-11-09 冷启动实测。
     */
    static boolean tableExists(Connection connection) throws Exception {
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet rs = metadata.getTables(connection.getCatalog(), null, "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                if (TABLE.equalsIgnoreCase(rs.getString("TABLE_NAME"))
                        && inCurrentSchema(connection, rs)) {
                    return true;
                }
            }
        }
        return false;
    }

    static boolean columnExists(Connection connection, String column) throws Exception {
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet rs = metadata.getColumns(connection.getCatalog(), null, "%", "%")) {
            while (rs.next()) {
                if (TABLE.equalsIgnoreCase(rs.getString("TABLE_NAME"))
                        && column.equalsIgnoreCase(rs.getString("COLUMN_NAME"))
                        && inCurrentSchema(connection, rs)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 只认当前 schema 的对象。
     *
     * <p>MySQL 的元数据里 {@code TABLE_SCHEM} 通常为 null（catalog 才是库），
     * 所以拿不到 schema 时按"同库"放行，不能因为 null 就把 MySQL 上所有表都判成不存在。
     * H2 会给出真实 schema，正好用它挡住 {@code INFORMATION_SCHEMA.USERS}。
     */
    private static boolean inCurrentSchema(Connection connection, ResultSet rs) throws Exception {
        String expected = connection.getSchema();
        if (expected == null || expected.isBlank()) {
            return true;
        }
        String actual = rs.getString("TABLE_SCHEM");
        return actual == null || actual.isBlank() || expected.equalsIgnoreCase(actual);
    }
}
