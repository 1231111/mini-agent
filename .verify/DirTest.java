import java.sql.*;
public class DirTest {
  public static void main(String[] a) throws Exception {
    Class.forName("org.h2.Driver");
    String url = "jdbc:h2:file:D:/AI/miniagent/.verify/h2dir-test/nested/data/miniagent;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE";
    try (Connection c = DriverManager.getConnection(url, "sa", "")) {
      c.createStatement().execute("create table t (id bigint)");
      System.out.println("  [OK] 父目录不存在时 H2 自行创建，连接成功");
    } catch (Exception e) {
      System.out.println("  [FAIL] " + e.getMessage().split("\n")[0]);
    }
  }
}
