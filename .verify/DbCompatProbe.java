import java.sql.*;

/**
 * 嵌入式库兼容性探针 v2。
 *
 * 把 MiniAgent 现有的 MySQL 方言 SQL 原样喂给目标库，看哪些能跑、哪些要改。
 *
 * 覆盖范围：
 *   A 组  实体层：两个基类的 TS_COL 常量、columnDefinition 里的列类型、3 处 nativeQuery
 *   B 组  迁移脚本层：db/migration/V*.sql 里的 MySQL 专有写法
 *         （桌面档 flyway.enabled=false 用不到，但如果哪天开了就会炸，先量出来）
 *
 * 用法：java -cp h2-2.2.224.jar DbCompatProbe.java "<jdbc-url>" "<user>" "<pass>"
 *   H2 + 兼容模式：jdbc:h2:mem:probe1;MODE=MySQL
 *   H2 默认模式  ：jdbc:h2:mem:probe2
 */
public class DbCompatProbe {

    static int pass = 0;
    static int fail = 0;

    static void run(Connection c, String label, String sql) {
        try (Statement st = c.createStatement()) {
            st.execute(sql);
            System.out.println("  [OK]   " + label);
            pass++;
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.toString() : e.getMessage().split("\n")[0];
            System.out.println("  [FAIL] " + label);
            System.out.println("         -> " + msg);
            fail++;
        }
    }

    static void expect(Connection c, String label, String sql, boolean shouldSucceed) {
        boolean ok;
        String msg = "";
        try (Statement st = c.createStatement()) {
            st.execute(sql);
            ok = true;
        } catch (Exception e) {
            ok = false;
            msg = e.getMessage() == null ? e.toString() : e.getMessage().split("\n")[0];
        }
        boolean verdict = (ok == shouldSucceed);
        System.out.println("  [" + (verdict ? "OK" : "??") + "]   " + label
                + "   (实测 " + (ok ? "通过" : "失败") + "，期望 " + (shouldSucceed ? "通过" : "失败") + ")");
        if (!verdict) {
            System.out.println("         -> " + msg);
            fail++;
        } else {
            pass++;
        }
    }

    public static void main(String[] args) throws Exception {
        String url = args[0];
        String user = args.length > 1 ? args[1] : "sa";
        String pwd = args.length > 2 ? args[2] : "";
        System.out.println("=== " + url + " ===");

        // 单文件源码模式下 DriverManager 的 ServiceLoader 有时扫不到驱动，显式加载。
        for (String cls : new String[]{"org.sqlite.JDBC", "org.h2.Driver"}) {
            try {
                Class.forName(cls);
                System.out.println("  (已加载驱动 " + cls + ")");
            } catch (ClassNotFoundException ignored) {
            }
        }

        try (Connection c = DriverManager.getConnection(url, user, pwd)) {

            System.out.println("\n-- A1. 两个基类的 TS_COL 常量（覆盖几乎所有表）--");
            run(c, "TS_COL: datetime(6) not null default current_timestamp(6)",
                "create table probe_base (id bigint primary key, "
                + "created_at datetime(6) not null default current_timestamp(6), "
                + "updated_at datetime(6) not null default current_timestamp(6))");

            System.out.println("\n-- A2. columnDefinition 里写死的列类型 --");
            run(c, "LONGTEXT", "create table probe_lob (id bigint primary key, content LONGTEXT)");
            run(c, "TEXT", "create table probe_text (id bigint primary key, note TEXT)");
            run(c, "VARCHAR(255) + ` 反引号列名",
                "create table probe_vc (id bigint primary key, `user` varchar(255))");
            run(c, "BIGINT auto_increment（@GeneratedValue IDENTITY）",
                "create table probe_ai (id bigint not null auto_increment, primary key (id))");

            System.out.println("\n-- A3. AgentTokenUsageRepository.increment --");
            run(c, "建表 agent_token_usage",
                "create table agent_token_usage (session_id varchar(64) primary key, "
                + "input_tokens bigint, output_tokens bigint, tool_calls int, llm_calls int, "
                + "version bigint, created_at datetime(6), updated_at datetime(6))");

            String tokenUpsert =
                "INSERT INTO agent_token_usage (session_id,input_tokens,output_tokens,tool_calls,llm_calls,version,created_at,updated_at) "
                + "VALUES ('s1',1,2,3,4,0,NOW(6),NOW(6)) "
                + "ON DUPLICATE KEY UPDATE input_tokens=input_tokens+1, output_tokens=output_tokens+2, "
                + "tool_calls=tool_calls+3, llm_calls=llm_calls+4, version=version+1, updated_at=NOW(6)";

            run(c, "increment 第 1 次 (NOW(6) + ON DUPLICATE KEY UPDATE)", tokenUpsert);
            run(c, "increment 第 2 次（验 upsert 是否真走 UPDATE 分支）", tokenUpsert);

            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery(
                     "select input_tokens, output_tokens, version from agent_token_usage where session_id='s1'")) {
                if (rs.next()) {
                    long in = rs.getLong(1), out = rs.getLong(2), ver = rs.getLong(3);
                    boolean ok = in == 2 && out == 4 && ver == 1;
                    System.out.println((ok ? "  [OK]   " : "  [FAIL] ")
                        + "累加语义: input=" + in + " output=" + out + " version=" + ver
                        + "  (期望 2 / 4 / 1；若为 1/2/0 说明走了 INSERT 而非 UPDATE)");
                    if (ok) pass++; else fail++;
                }
            } catch (Exception e) {
                System.out.println("  [FAIL] 校验累加语义 -> " + e.getMessage());
                fail++;
            }

            System.out.println("\n-- A4. AuthSessionRepository.revokeAllForTenant --");
            run(c, "建表 users / auth_sessions",
                "create table users (id bigint primary key, tenant_id bigint)");
            run(c, "  auth_sessions",
                "create table auth_sessions (id bigint primary key, user_id bigint, revoked boolean)");
            run(c, "UPDATE ... INNER JOIN ... SET（MySQL 多表更新）",
                "UPDATE auth_sessions s INNER JOIN users u ON u.id = s.user_id "
                + "SET s.revoked = TRUE WHERE u.tenant_id = 1 AND s.revoked = FALSE");

            System.out.println("\n-- A5. TenantDailyUsageRepository.increment --");
            run(c, "建表 tenant_daily_usage",
                "create table tenant_daily_usage (id bigint primary key auto_increment, "
                + "tenant_id bigint, usage_date date, token_count bigint, "
                + "created_at datetime(6), updated_at datetime(6), "
                + "constraint uk_tenant_daily_usage unique (tenant_id, usage_date))");
            run(c, "increment (NOW(6) + ON DUPLICATE KEY UPDATE)",
                "INSERT INTO tenant_daily_usage (tenant_id,usage_date,token_count,created_at,updated_at) "
                + "VALUES (1,'2026-09-24',100,NOW(6),NOW(6)) "
                + "ON DUPLICATE KEY UPDATE token_count=token_count+100, updated_at=NOW(6)");

            System.out.println("\n-- B. db/migration/V*.sql 里的 MySQL 专有写法 --");
            System.out.println("   （桌面档 flyway.enabled=false，跑不到；此处只量一旦开启的代价）");
            expect(c, "DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)（大写，V1 原文）",
                "create table m1 (id bigint not null auto_increment, "
                + "created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), primary key (id))", true);
            expect(c, "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci",
                "create table m2 (id bigint not null, primary key (id)) "
                + "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci", true);
            expect(c, "UNIQUE KEY uk_x (col)",
                "create table m3 (id bigint, username varchar(50), UNIQUE KEY uk_m3_username (username))", true);
            expect(c, "KEY idx_x (a,b)",
                "create table m4 (a bigint, b bigint, KEY idx_m4_ab (a,b))", true);
            expect(c, "ALTER TABLE ... ADD COLUMN（V2/V5 之类）",
                "alter table m1 add column note LONGTEXT", true);
            expect(c, "DROP TABLE IF EXISTS ... CASCADE",
                "drop table if exists m9 cascade", true);
        }

        System.out.println("\n  小计: " + pass + " OK / " + fail + " FAIL(或与期望不符)");
    }
}
