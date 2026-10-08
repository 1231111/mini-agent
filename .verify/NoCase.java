import java.sql.*;
public class NoCase {
  public static void main(String[] a) throws Exception {
    Class.forName("org.h2.Driver");
    try (Connection c = DriverManager.getConnection("jdbc:h2:file:" + a[0] + ";MODE=MySQL", "sa", "")) {
      System.out.print("  schemas: ");
      try (ResultSet rs = c.getMetaData().getSchemas()) { while (rs.next()) System.out.print(rs.getString(1) + " "); }
      System.out.println();
      int n = 0; StringBuilder sb = new StringBuilder();
      try (ResultSet rs = c.getMetaData().getTables(null, null, "%", new String[]{"TABLE"})) {
        while (rs.next()) {
          String nm = rs.getString("TABLE_NAME");
          if (nm.toUpperCase().startsWith("INFORMATION_SCHEMA")) continue;
          n++; sb.append(nm).append(" ");
        }
      }
      System.out.println("  表数: " + n);
      System.out.println("  表名: " + sb.toString().trim());
      System.out.println("  大写搜索 users 命中: " + like(c, "USERS"));
      System.out.println("  小写搜索 users 命中: " + like(c, "users"));
    }
  }
  static boolean like(Connection c, String pat) throws Exception {
    try (ResultSet rs = c.getMetaData().getTables(null, null, pat, new String[]{"TABLE"})) { return rs.next(); }
  }
}
