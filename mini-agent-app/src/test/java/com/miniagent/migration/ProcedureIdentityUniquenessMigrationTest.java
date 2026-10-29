package com.miniagent.migration;

import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * V13 的效果必须在真库上验证：它要先把存量的同名行合并掉，唯一索引才建得出来。
 *
 * <p>如果迁移只建索引、不先清重复，在真实的存量库上会直接失败 —— 应用起不来。
 * 这条路径只能用真实的 H2（MySQL 兼容模式）跑，mock 看不见 DDL 冲突。</p>
 */
class ProcedureIdentityUniquenessMigrationTest {

    @Test
    void mergesDuplicateIdentitiesThenEnforcesUniqueness() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:procedure-identity-migration;MODE=MySQL;DB_CLOSE_DELAY=-1")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TABLE agent_procedures (
                            id BIGINT AUTO_INCREMENT PRIMARY KEY,
                            tenant_id VARCHAR(64) NOT NULL,
                            scope_type VARCHAR(32) NOT NULL,
                            scope_id VARCHAR(128) NOT NULL,
                            name VARCHAR(256) NOT NULL,
                            steps_json LONGTEXT NOT NULL,
                            status VARCHAR(32),
                            procedure_version INT,
                            success_count BIGINT,
                            attempt_count BIGINT
                        )
                        """);
                // 修复前的典型现场：同名三次写入 = 三行 v1，其中一行大小写不同
                statement.execute("""
                        INSERT INTO agent_procedures
                            (tenant_id, scope_type, scope_id, name, steps_json, status, procedure_version)
                        VALUES ('tenant-a', 'USER', '9', 'Release Checklist', '["a"]', 'ACTIVE', 1)
                        """);
                statement.execute("""
                        INSERT INTO agent_procedures
                            (tenant_id, scope_type, scope_id, name, steps_json, status, procedure_version)
                        VALUES ('tenant-a', 'USER', '9', 'release checklist', '["a","b"]', 'ACTIVE', 1)
                        """);
                statement.execute("""
                        INSERT INTO agent_procedures
                            (tenant_id, scope_type, scope_id, name, steps_json, status, procedure_version)
                        VALUES ('tenant-a', 'USER', '9', 'Release Checklist', '["a","b","c"]', NULL, 1)
                        """);
                // 不同名字、以及不同归属域的同名，都不该被合并
                statement.execute("""
                        INSERT INTO agent_procedures
                            (tenant_id, scope_type, scope_id, name, steps_json, status, procedure_version)
                        VALUES ('tenant-a', 'USER', '9', 'Release Notes', '[]', 'ACTIVE', 1)
                        """);
                statement.execute("""
                        INSERT INTO agent_procedures
                            (tenant_id, scope_type, scope_id, name, steps_json, status, procedure_version)
                        VALUES ('tenant-a', 'USER', '10', 'Release Checklist', '[]', 'ACTIVE', 1)
                        """);
            }

            Context context = mock(Context.class);
            when(context.getConnection()).thenReturn(connection);
            V13__ProcedureIdentityUniqueness migration = new V13__ProcedureIdentityUniqueness();
            migration.migrate(context);

            assertEquals(1, count(connection,
                            "SELECT COUNT(*) AS c FROM agent_procedures "
                                    + "WHERE status = 'ACTIVE' AND name = 'Release Checklist' "
                                    + "AND scope_id = '9'"),
                    "同名三行只能留一行 ACTIVE（保留 id 最大的那次写入）");
            assertEquals(2, count(connection,
                            "SELECT COUNT(*) AS c FROM agent_procedures WHERE name LIKE '%#archived-%'"),
                    "被合并的两行不删除，改名成 #archived-<id> 留档");
            assertEquals(2, count(connection,
                            "SELECT COUNT(*) AS c FROM agent_procedures "
                                    + "WHERE scope_id = '9' AND status = 'ACTIVE'"),
                    "不同名字的 SOP 不受影响");
            assertEquals(0, count(connection, "SELECT COUNT(*) AS c FROM agent_procedures WHERE status IS NULL"),
                    "status 为 NULL 的历史行要被归一成 ACTIVE，否则身份的可见性会依赖空值语义");

            // 唯一索引真的生效：同名再来一行必须被拒
            try (Statement statement = connection.createStatement()) {
                assertThrows(Exception.class, () -> statement.execute("""
                        INSERT INTO agent_procedures
                            (tenant_id, scope_type, scope_id, name, steps_json, status, procedure_version)
                        VALUES ('tenant-a', 'USER', '9', 'Release Checklist', '[]', 'ACTIVE', 1)
                        """));
            }

            // 再跑一次必须无副作用（Flyway 校验和保证不重跑，但迁移本身要能幂等）
            migration.migrate(context);
            assertEquals(2, count(connection,
                    "SELECT COUNT(*) AS c FROM agent_procedures WHERE name LIKE '%#archived-%'"));
        }
    }

    /**
     * 全新桌面安装：Flyway 先于 Hibernate 建表执行，此时表还不存在。
     * 跳过即可 —— 表由 {@code ddl-auto: update} 按实体建，唯一键来自实体注解。
     */
    @Test
    void skipsWhenTableDoesNotExistYet() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:procedure-identity-empty;MODE=MySQL;DB_CLOSE_DELAY=-1")) {
            Context context = mock(Context.class);
            when(context.getConnection()).thenReturn(connection);

            new V13__ProcedureIdentityUniqueness().migrate(context);
        }
    }

    private int count(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getInt("c");
        }
    }
}
