import java.sql.*;
public class SchemaProbe {
  public static void main(String[] a) throws Exception {
    Class.forName("org.h2.Driver");
    String base = "jdbc:h2:file:" + a[0];
    String[] urls = {
      base + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
      base + ";MODE=MySQL",
    };
    String[] labels = {"带 DATABASE_TO_LOWER + CASE_INSENSITIVE_IDENTIFIERS", "只带 MODE=MySQL"};
    for (int k = 0; k < urls.length; k++) {
      System.out.println("\n===== " + labels[k] + " =====");
      try (Connection c = DriverManager.getConnection(urls[k], "sa", "")) {
        System.out.println("  schemas: ");
        try (ResultSet rs = c.getMetaData().getSchemas()) {
          while (rs.next()) System.out.println("    - " + rs.getString(1));
        }
        int total = 0, dup = 0;
        java.util.Map<String,Integer> seen = new java.util.HashMap<>();
        try (ResultSet rs = c.getMetaData().getTables(null, null, "%", new String[]{"TABLE"})) {
          while (rs.next()) {
            String s = rs.getString("TABLE_SCHEM"), n = rs.getString("TABLE_NAME");
            if (n.toUpperCase().startsWith("INFORMATION_SCHEMA")) continue;
            String key = (s == null ? "?" : s) + ":" + n;
            seen.merge(key, 1, Integer::sum);
            total++;
          }
        }
        System.out.println("  表条目数(含重复): " + total);
        for (java.util.Map.Entry<String,Integer> e : new java.util.TreeMap<>(seen).entrySet()) {
          if (e.getValue() > 1) { System.out.println("    重复: " + e.getKey() + " x" + e.getValue()); dup++; }
        }
        System.out.println("  重复条目数: " + dup);
        System.out.println("  唯一表数: " + seen.size());
      } catch (Exception e) {
        System.out.println("  [FAIL] " + e.getMessage().split("\n")[0]);
      }
    }
  }
}
