import java.sql.*;
import java.util.*;

public class LobProbe2 {
  static String url;
  public static void main(String[] a) throws Exception {
    Class.forName("org.h2.Driver");
    url = "jdbc:h2:file:" + a[0] + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE";
    try (Connection c = DriverManager.getConnection(url, "sa", "")) {
      String[][] targets = {
        {"agent_memory_entries", "content"},
        {"chat_tasks", "question"},
        {"agent_events", "payload_json"},
      };
      for (String[] t : targets) probe(c, t[0], t[1]);
    }
  }

  static void probe(Connection c, String table, String lobCol) throws Exception {
    System.out.println("\n===== " + table + "." + lobCol + " =====");
    LinkedHashMap<String, String> cols = new LinkedHashMap<>();
    try (ResultSet rs = c.getMetaData().getColumns(null, null, table, "%")) {
      while (rs.next()) {
        cols.put(rs.getString("COLUMN_NAME"), rs.getString("TYPE_NAME"));
      }
    }

    String big = "x".repeat(300_000) + "_中文尾巴";

    // 生成一条能满足 NOT NULL 的插入
    StringBuilder names = new StringBuilder(), marks = new StringBuilder();
    List<String> order = new ArrayList<>();
    for (Map.Entry<String, String> e : cols.entrySet()) {
      String n = e.getKey();
      names.append(n).append(",");
      marks.append("?,");
      order.add(n);
    }
    names.setLength(names.length() - 1);
    marks.setLength(marks.length() - 1);
    String sql = "insert into " + table + " (" + names + ") values (" + marks + ")";

    try (PreparedStatement ps = c.prepareStatement(sql)) {
      int i = 1;
      for (String n : order) {
        if (n.equalsIgnoreCase(lobCol)) {
          Clob clob = c.createClob();
          clob.setString(1, big);
          ps.setClob(i++, clob);
        } else if (n.equalsIgnoreCase("id")) {
          ps.setLong(i++, 999777L);
        } else {
          String tn = cols.get(n);
          if (tn.contains("VARCHAR") || tn.contains("CHAR") || tn.contains("CLOB")) ps.setString(i++, "probe");
          else if (tn.contains("BIGINT")) ps.setLong(i++, 1L);
          else if (tn.contains("INT")) ps.setInt(i++, 1);
          else if (tn.contains("BOOL")) ps.setBoolean(i++, false);
          else if (tn.contains("TIMESTAMP") || tn.contains("DATE")) ps.setTimestamp(i++, new Timestamp(System.currentTimeMillis()));
          else ps.setObject(i++, null);
        }
      }
      try {
        ps.executeUpdate();
        System.out.println("  [OK]   setClob 写入 " + big.length() + " 字符");
      } catch (Exception e) {
        System.out.println("  [FAIL] setClob -> " + e.getMessage().split("\n")[0]);
        return;
      }
    } catch (Exception e) {
      System.out.println("  [SKIP] 构造插入失败: " + e.getMessage().split("\n")[0]);
      return;
    }

    try (PreparedStatement ps = c.prepareStatement(
            "select " + lobCol + " from " + table + " where id=999777")) {
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) {
          String back = rs.getString(1);
          boolean ok = big.equals(back);
          System.out.println((ok ? "  [OK]   " : "  [FAIL] ")
              + "getString 读回 length=" + (back == null ? -1 : back.length())
              + " 期望=" + big.length() + " 完全一致=" + ok);
        }
      }
    }

    // 再用 getClob 读一次
    try (PreparedStatement ps = c.prepareStatement(
            "select " + lobCol + " from " + table + " where id=999777")) {
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) {
          Clob cl = rs.getClob(1);
          System.out.println("  [OK]   getClob 读回 length=" + (cl == null ? -1 : cl.length()));
        }
      }
    } catch (Exception e) {
      System.out.println("  [FAIL] getClob -> " + e.getMessage().split("\n")[0]);
    }
  }
}
