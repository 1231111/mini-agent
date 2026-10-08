import java.sql.*;

public class ChatDb {
  public static void main(String[] a) throws Exception {
    Class.forName("org.h2.Driver");
    try (Connection c = DriverManager.getConnection("jdbc:h2:file:" + a[0] + ";MODE=MySQL", "sa", "")) {
      show(c, "chat_conversations", "select id, user_id, title from chat_conversations");
      show(c, "chat_tasks", "select id, session_id, length(question) as q_len, length(answer) as a_len, "
          + "substr(answer,1,90) as answer_head, deleted from chat_tasks");
      show(c, "chat_messages", "select id, conversation_id, role, length(content) as c_len from chat_messages");
      show(c, "agent_token_usage", "select session_id, input_tokens, output_tokens, tool_calls, llm_calls, version from agent_token_usage");
      show(c, "agent_trace_steps", "select count(*) as 行数 from agent_trace_steps");
      show(c, "agent_task_runs", "select count(*) as 行数 from agent_task_runs");
      show(c, "agent_events", "select count(*) as 行数 from agent_events");
      show(c, "agent_working_memories", "select count(*) as 行数 from agent_working_memories");
      show(c, "agent_session_planner", "select count(*) as 行数 from agent_session_planner");
      show(c, "tenant_daily_usage", "select tenant_id, usage_date, token_count from tenant_daily_usage");
    }
  }
  static void show(Connection c, String label, String sql) {
    System.out.println("-- " + label + " --");
    try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
      ResultSetMetaData md = rs.getMetaData();
      int n = md.getColumnCount(), rows = 0;
      while (rs.next()) {
        rows++;
        StringBuilder sb = new StringBuilder("    ");
        for (int i = 1; i <= n; i++) sb.append(md.getColumnLabel(i)).append("=").append(rs.getString(i)).append("  ");
        System.out.println(sb.toString().trim());
      }
      System.out.println("    行数: " + rows);
    } catch (Exception e) {
      System.out.println("    [FAIL] " + e.getMessage().split("\n")[0]);
    }
  }
}
