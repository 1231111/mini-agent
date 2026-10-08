import java.sql.*;
public class LobProbe {
  public static void main(String[] a) throws Exception {
    Class.forName("org.h2.Driver");
    String url = "jdbc:h2:file:" + a[0] + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE";
    try (Connection c = DriverManager.getConnection(url, "sa", "")) {
      System.out.println("-- 1. Hibernate 把 LONGTEXT 落成了什么列类型 --");
      String[] probe = {"chat_tasks", "question", "agent_memory_entries", "content",
                        "agent_session_planner", "state_json", "agent_events", "payload_json"};
      for (int i = 0; i < probe.length; i += 2) {
        try (ResultSet rs = c.getMetaData().getColumns(null, null, probe[i], probe[i+1])) {
          if (rs.next()) {
            System.out.printf("  %-26s %-14s -> %s (%d)%n",
                probe[i] + "." + probe[i+1], rs.getString("TYPE_NAME"),
                rs.getString("TYPE_NAME"), rs.getInt("COLUMN_SIZE"));
          } else {
            System.out.println("  " + probe[i] + "." + probe[i+1] + " 列不存在");
          }
        }
      }

      System.out.println("\n-- 2. 用 Clob 绑定写入（Hibernate @Lob 走的就是这条路径）--");
      String big = "x".repeat(200_000) + "中文结尾";
      try (PreparedStatement ps = c.prepareStatement(
              "insert into agent_memory_entries (id, content, created_at, updated_at) values (?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)")) {
        ps.setLong(1, 999001L);
        Clob clob = c.createClob();
        clob.setString(1, big);
        ps.setClob(2, clob);
        ps.executeUpdate();
        System.out.println("  [OK] setClob 写入 200000 字符成功");
      } catch (Exception e) {
        System.out.println("  [FAIL] setClob -> " + e.getMessage().split("\n")[0]);
      }

      try (PreparedStatement ps = c.prepareStatement(
              "select content from agent_memory_entries where id=?")) {
        ps.setLong(1, 999001L);
        try (ResultSet rs = ps.executeQuery()) {
          if (rs.next()) {
            String back = rs.getString(1);
            boolean ok = back != null && back.length() == big.length() && back.equals(big);
            System.out.println((ok ? "  [OK]   " : "  [FAIL] ")
                + "getString 读回长度=" + (back == null ? -1 : back.length())
                + " 期望=" + big.length() + " 内容一致=" + ok);
          }
        }
      } catch (Exception e) {
        System.out.println("  [FAIL] 读回 -> " + e.getMessage().split("\n")[0]);
      }

      System.out.println("\n-- 3. 两处 upsert 在真实表上跑一遍 --");
      String up = "INSERT INTO agent_token_usage (session_id,input_tokens,output_tokens,tool_calls,llm_calls,version,created_at,updated_at) "
          + "VALUES ('probe',1,2,3,4,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) "
          + "ON DUPLICATE KEY UPDATE input_tokens=input_tokens+1, output_tokens=output_tokens+2, "
          + "tool_calls=tool_calls+3, llm_calls=llm_calls+4, version=version+1, updated_at=CURRENT_TIMESTAMP";
      try (Statement st = c.createStatement()) {
        st.execute(up); st.execute(up); st.execute(up);
        try (ResultSet rs = st.executeQuery("select input_tokens,output_tokens,version from agent_token_usage where session_id='probe'")) {
          rs.next();
          boolean ok = rs.getLong(1) == 3 && rs.getLong(2) == 6 && rs.getLong(3) == 2;
          System.out.println((ok ? "  [OK]   " : "  [FAIL] ") + "三次 upsert -> input=" + rs.getLong(1)
              + " output=" + rs.getLong(2) + " version=" + rs.getLong(3) + " (期望 3/6/2)");
        }
      } catch (Exception e) {
        System.out.println("  [FAIL] upsert -> " + e.getMessage().split("\n")[0]);
      }
    }
  }
}
