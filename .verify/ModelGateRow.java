import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * 往 user_model_config 里塞一行"存量行"：custom_base_url 指向攻击者地址。
 *
 * 用途：验证 UserModelConfigService.resolve() 的闸门 —— save() 不写这个字段之后，
 * 历史行里可能还留着老值（开关是后加的），只堵 save() 不堵 resolve() 等于留后门。
 *
 * 必须在应用停止时跑（H2 文件模式单进程独占）。
 *
 * 用法：java -cp "<h2.jar>;." ModelGateRow <h2库文件路径(不含jdbc前缀)> <username> <恶意baseUrl>
 *   e.g. java -cp "%USERPROFILE%\.m2\repository\com\h2database\h2\2.3.232\h2-2.3.232.jar;." \
 *             ModelGateRow D:/AI/miniagent/.verify/home-gate/data/miniagent gate https://legacy-attacker.example.com/v1
 */
public class ModelGateRow {

    public static void main(String[] a) throws Exception {
        Class.forName("org.h2.Driver");
        String url = "jdbc:h2:file:" + a[0] + ";MODE=MySQL";
        String username = a[1];
        String evil = a[2];

        try (Connection c = DriverManager.getConnection(url, "sa", "")) {

            long userId;
            try (PreparedStatement ps = c.prepareStatement(
                    "select id from users where username = ?")) {
                ps.setString(1, username);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        System.out.println("[FAIL] users 表里没有 " + username);
                        System.exit(1);
                        return;
                    }
                    userId = rs.getLong(1);
                }
            }
            System.out.println("userId = " + userId);

            // 先清掉可能存在的行，保证是纯"存量行"注入
            try (PreparedStatement ps = c.prepareStatement(
                    "delete from user_model_config where user_id = ?")) {
                ps.setLong(1, userId);
                System.out.println("deleted = " + ps.executeUpdate());
            }

            try (PreparedStatement ps = c.prepareStatement(
                    "insert into user_model_config "
                  + "(user_id, preset_id, custom_base_url, custom_model_name, custom_api_key, "
                  + " created_at, updated_at) "
                  + "values (?, 'default', ?, null, null, current_timestamp, current_timestamp)")) {
                ps.setLong(1, userId);
                ps.setString(2, evil);
                System.out.println("inserted = " + ps.executeUpdate());
            }

            try (Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery(
                     "select id, user_id, preset_id, custom_base_url from user_model_config")) {
                while (rs.next()) {
                    System.out.printf("row: id=%d user_id=%d preset=%s custom_base_url=%s%n",
                            rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4));
                }
            }
        }
        System.out.println("[OK] 存量行注入完成（custom_base_url=" + evil + "）");
    }
}
