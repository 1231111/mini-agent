import java.sql.*;
public class ShowTables {
  public static void main(String[] a) throws Exception {
    Class.forName("org.h2.Driver");
    String url = "jdbc:h2:file:" + a[0] + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE";
    try (Connection c = DriverManager.getConnection(url, "sa", "")) {
      java.util.List<String> tabs = new java.util.ArrayList<>();
      try (ResultSet rs = c.getMetaData().getTables(null, null, "%", new String[]{"TABLE"})) {
        while (rs.next()) {
          String name = rs.getString("TABLE_NAME");
          if (name.toUpperCase().startsWith("INFORMATION_SCHEMA")) continue;
          tabs.add(name);
        }
      }
      java.util.Collections.sort(tabs);
      System.out.println("建出表数量: " + tabs.size());
      for (String t : tabs) {
        int cols = 0;
        try (ResultSet rs = c.getMetaData().getColumns(null, null, t, "%")) {
          while (rs.next()) cols++;
        }
        System.out.println("  " + t + "  (列 " + cols + ")");
      }
    }
  }
}
