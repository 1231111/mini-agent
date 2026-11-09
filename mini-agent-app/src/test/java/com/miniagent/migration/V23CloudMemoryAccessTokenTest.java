package com.miniagent.migration;

import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * V23 在「全新安装的空库」上不能炸。
 *
 * <p>桌面档用 {@code ddl-auto: update} 由实体建表，而 Flyway 迁移跑在建表之前，
 * 所以首次启动时 {@code PUBLIC.users} 还不存在。H2 的 {@code INFORMATION_SCHEMA}
 * 里恰好有一张同名的系统表 {@code USERS}；旧写法
 * {@code getTables(catalog, null, "%", ...)} 不过滤 schema，会把它当成业务表，
 * 然后执行 {@code ALTER TABLE users ...} 报 {@code Table "USERS" not found}，
 * 新装机直接启动失败。
 */
class V23CloudMemoryAccessTokenTest {

    /** 每个用例一个独立库名，避免互相污染；不能往 URL 末尾拼后缀，那会落进 H2 参数里。 */
    private static String url(String name) {
        return "jdbc:h2:mem:" + name + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
    }

    /** 反向验证前提：H2 确实存在 INFORMATION_SCHEMA.USERS，否则这个回归测试没有意义。 */
    @Test
    void informationSchemaReallyHasAUsersTable() throws Exception {
        try (Connection connection = DriverManager.getConnection(url("v23_schema_probe"))) {
            assertTrue(count(connection,
                    "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES "
                            + "WHERE TABLE_SCHEMA='INFORMATION_SCHEMA' AND TABLE_NAME='USERS'") > 0,
                    "H2 版本若不再提供 INFORMATION_SCHEMA.USERS，这条回归用例需重新评估");
        }
    }

    @Test
    void emptyDatabaseIsNotMistakenForHavingBusinessUsersTable() throws Exception {
        try (Connection connection = DriverManager.getConnection(url("v23_empty_db"))) {
            assertFalse(V23__CloudMemoryAccessToken.tableExists(connection),
                    "空库上不能把 INFORMATION_SCHEMA.USERS 当成业务表 users");

            // 迁移本身在空库上必须安静跳过，而不是抛异常。
            Context context = mock(Context.class);
            when(context.getConnection()).thenReturn(connection);
            new V23__CloudMemoryAccessToken().migrate(context);
        }
    }

    @Test
    void realUsersTableGetsThreeColumnsAndMigrationIsIdempotent() throws Exception {
        try (Connection connection = DriverManager.getConnection(url("v23_real_db"))) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE users (id BIGINT PRIMARY KEY)");
            }
            assertTrue(V23__CloudMemoryAccessToken.tableExists(connection));
            assertFalse(V23__CloudMemoryAccessToken.columnExists(connection, "cloud_tenant_id"));

            Context context = mock(Context.class);
            when(context.getConnection()).thenReturn(connection);
            V23__CloudMemoryAccessToken migration = new V23__CloudMemoryAccessToken();
            migration.migrate(context);
            migration.migrate(context); // 重复执行不能报"列已存在"

            assertEquals(3, columnCount(connection));
            assertTrue(V23__CloudMemoryAccessToken.columnExists(connection, "cloud_tenant_id"));
            assertTrue(V23__CloudMemoryAccessToken.columnExists(connection, "memory_access_token"));
            assertTrue(V23__CloudMemoryAccessToken.columnExists(connection, "memory_refresh_credential"));
        }
    }

    private static long count(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static long columnCount(Connection connection) throws Exception {
        return count(connection,
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS "
                        + "WHERE TABLE_SCHEMA='PUBLIC' AND TABLE_NAME='USERS' "
                        + "AND COLUMN_NAME IN ('CLOUD_TENANT_ID','MEMORY_ACCESS_TOKEN','MEMORY_REFRESH_CREDENTIAL')");
    }
}
