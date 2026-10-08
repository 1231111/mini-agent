import java.sql.*;
public class CaseQuery {
  public static void main(String[] a) throws Exception {
    Class.forName("org.h2.Driver");
    try (Connection c = DriverManager.getConnection("jdbc:h2:file:" + a[0] + ";MODE=MySQL", "sa", "")) {
      // Hibernate 生成的 SQL 用的是实体里写的小写、未加引号的标识符
      String[] sqls = {
        "select id, username from users where username = 'nobody'",
        "insert into tenants (id, name, created_at, updated_at) values (777, 'probe', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
        "insert into chat_tasks (user_id, session_id, question, created_at, updated_at) values (1,'s','长文本',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
        "select count(*) from chat_tasks where session_id='s'",
        // 两处 nativeQuery 原样（Hibernate 对 native query 不做改写）
        "INSERT INTO agent_token_usage (session_id,input_tokens,output_tokens,tool_calls,llm_calls,version,created_at,updated_at) "
          + "VALUES ('cq',1,2,3,4,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) "
          + "ON DUPLICATE KEY UPDATE input_tokens=input_tokens+1, version=version+1",
        "INSERT INTO tenant_daily_usage (tenant_id,usage_date,token_count,created_at,updated_at) "
          + "VALUES (777,'2026-09-24',10,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) "
          + "ON DUPLICATE KEY UPDATE token_count=token_count+10",
        // 改写后的 JPQL 会生成：update auth_sessions set revoked=1 where revoked=0 and user_id in (select u.id from users u where u.tenant_id=?)
        "update auth_sessions set revoked=true where revoked=false and user_id in (select u.id from users u where u.tenant_id=777)",
      };
      for (String s : sqls) {
        try (Statement st = c.createStatement()) {
          st.execute(s);
          System.out.println("  [OK]   " + s.substring(0, Math.min(78, s.length())));
        } catch (Exception e) {
          System.out.println("  [FAIL] " + s.substring(0, Math.min(78, s.length())));
          System.out.println("         -> " + e.getMessage().split("\n")[0]);
        }
      }
    }
  }
}
