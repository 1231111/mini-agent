import java.sql.*;
public class Sessions {
  public static void main(String[] a) throws Exception {
    Class.forName("org.h2.Driver");
    try (Connection c = DriverManager.getConnection("jdbc:h2:file:" + a[0] + ";MODE=MySQL", "sa", "")) {
      System.out.println("-- users 表 --");
      try (Statement st = c.createStatement();
           ResultSet rs = st.executeQuery("select id, username, display_name, tenant_id, created_at, updated_at from users")) {
        while (rs.next()) {
          System.out.printf("  id=%d username=%s display=%s tenant=%d created=%s updated=%s%n",
            rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getString(5), rs.getString(6));
        }
      }
      System.out.println("-- auth_sessions 表（会话记录真相源）--");
      try (Statement st = c.createStatement();
           ResultSet rs = st.executeQuery("select token_hash, user_id, expires_at, revoked, last_seen_at from auth_sessions")) {
        int n = 0;
        while (rs.next()) {
          n++;
          System.out.printf("  token_hash=%s... (len=%d) user=%d revoked=%s%n     expires_at=%s  last_seen_at=%s%n",
            rs.getString(1).substring(0, 16), rs.getString(1).length(), rs.getLong(2),
            rs.getBoolean(4), rs.getString(3), rs.getString(5));
        }
        System.out.println("  行数: " + n);
      }
      System.out.println("-- agent_token_usage（native upsert 是否真被调用过）--");
      try (Statement st = c.createStatement();
           ResultSet rs = st.executeQuery("select session_id, input_tokens, output_tokens, version from agent_token_usage")) {
        int n = 0;
        while (rs.next()) { n++; System.out.printf("  session=%s in=%d out=%d ver=%d%n",
            rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)); }
        System.out.println("  行数: " + n);
      }
    }
  }
}
