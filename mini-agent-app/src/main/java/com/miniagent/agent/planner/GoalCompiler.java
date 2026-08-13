package com.miniagent.agent.planner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.agent.intent.IntentType;
import com.miniagent.agent.intent.TaskPlan;
import com.miniagent.agent.intent.TaskStep;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * NL + Intent → Goal + TaskGraph。LLM JSON 拆解 + schema/环校验；失败走模板图。
 */
@Component
public class GoalCompiler {

    private static final Logger log = LoggerFactory.getLogger(GoalCompiler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String COMPILER_SYSTEM = """
            你是任务图编译器。把用户目标拆成 DAG JSON，只输出 JSON：
            {
              "objective":"...",
              "constraints":["..."],
              "successCriteria":["..."],
              "entities":{"k":"v"},
              "nodes":[
                {"id":"n1","name":"...","capability":"file_write|web|code|image|browser|shell|research|deliver|plan|general",
                 "dependsOn":[],"priority":10,"doneWhen":"file_exists:path或note_required或media_delivered",
                 "toolHint":"可选工具名"}
              ]
            }
            规则：读链接/飞书/wiki 写文件用 1 个节点（browser 提取后立刻 write_file），
            禁止拆成打开/密码/目录/提取/写入。复杂工程才 ≥3 节点。
            依赖无环；每节点必须有 doneWhen；id 唯一。落盘用 file_exists:相对路径。
            toolHint 必须是已注册工具名（browser_navigate / browser_type / write_file），
            禁止编造 feishu_*、browser_vision、document_parser、markdown_file_writer。
            """;

    private final PlannerProperties properties;

    public GoalCompiler(PlannerProperties properties) {
        this.properties = properties;
    }

    public record CompileResult(Goal goal, TaskGraph graph, boolean fromTemplate) {}

    public CompileResult compile(ChatModel chat, String userMessage, TaskPlan plan) {
        Goal base = goalFromPlan(userMessage, plan);
        int retries = Math.max(0, properties.getCompilerRetry());
        for (int i = 0; i <= retries; i++) {
            try {
                TaskGraph g = compileWithLlm(chat, userMessage, plan);
                if (g != null && validate(g, plan)) {
                    if (looksLikeFetchWrite(userMessage, plan) && g.nodes().size() > 1)
                        g = fetchWriteTemplate();
                    Goal goal = new Goal(base.goalId(), base.objective(), base.intent(),
                            base.entities(), base.constraints(), base.successCriteria());
                    return new CompileResult(goal, markPending(g), false);
                }
            } catch (Exception e) {
                log.warn("GoalCompiler LLM 拆解失败 retry={}: {}", i, e.getMessage());
            }
        }
        TaskGraph fallback = templateGraph(userMessage, plan);
        log.info("GoalCompiler 使用模板图 nodes={}", fallback.nodes().size());
        return new CompileResult(base, fallback, true);
    }

    private Goal goalFromPlan(String userMessage, TaskPlan plan) {
        String objective = plan != null && StringUtils.isNotBlank(plan.taskGoal())
                ? plan.taskGoal() : (userMessage == null ? "" : userMessage);
        String intent = plan != null && plan.intent() != null ? plan.intent().name() : "UNKNOWN";
        List<String> criteria = new ArrayList<>();
        if (plan != null && plan.steps() != null)
            for (TaskStep s : plan.steps())
                if (s != null && StringUtils.isNotBlank(s.goal())) criteria.add(s.goal());
        if (criteria.isEmpty()) criteria.add("完成 objective 且可验收");
        return new Goal(
                "goal_" + UUID.randomUUID().toString().substring(0, 8),
                objective, intent, Map.of(), List.of(), criteria);
    }

    private TaskGraph compileWithLlm(ChatModel chat, String userMessage, TaskPlan plan) {
        if (chat == null) return null;
        String user = "用户消息:\n" + userMessage
                + "\n\n意图:" + (plan == null ? "UNKNOWN" : plan.intent())
                + "\n任务目标:" + (plan == null ? "" : plan.taskGoal());
        var response = chat.chat(ChatRequest.builder()
                .messages(List.of(new SystemMessage(COMPILER_SYSTEM), UserMessage.from(user)))
                .build());
        String text = response.aiMessage() == null ? "" : response.aiMessage().text();
        try {
            return parseGraph(text);
        } catch (Exception e) {
            throw new IllegalStateException("parse graph failed: " + e.getMessage(), e);
        }
    }

    TaskGraph parseGraph(String text) throws Exception {
        String json = extractJson(text);
        JsonNode root = MAPPER.readTree(json);
        JsonNode nodes = root.get("nodes");
        if (nodes == null || !nodes.isArray()) return new TaskGraph(List.of());
        List<TaskNode> list = new ArrayList<>();
        for (JsonNode n : nodes) {
            String id = textOr(n, "id", "n" + (list.size() + 1));
            String name = textOr(n, "name", id);
            String cap = textOr(n, "capability", "general");
            List<String> deps = new ArrayList<>();
            JsonNode d = n.get("dependsOn");
            if (d == null) d = n.get("depends_on");
            if (d != null && d.isArray())
                for (JsonNode x : d) deps.add(x.asText());
            int priority = n.has("priority") ? n.get("priority").asInt(5) : 5;
            String doneWhen = textOr(n, "doneWhen", textOr(n, "done_when", "note_required"));
            String toolHint = textOr(n, "toolHint", textOr(n, "tool_hint", ""));
            list.add(new TaskNode(id, name, cap, deps, TaskNodeStatus.PENDING,
                    priority, doneWhen, toolHint, "", 0));
        }
        return new TaskGraph(list);
    }

    boolean validate(TaskGraph g, TaskPlan plan) {
        if (g == null || g.isEmpty()) return false;
        if (g.hasCycle()) return false;
        boolean needRich = plan != null && (plan.requiresStructuredPlan()
                || (plan.intent() != IntentType.QUESTION && plan.intent() != IntentType.REVIEW));
        if (needRich && g.nodes().size() < minNodes(g)) return false;
        for (TaskNode n : g.nodes()) {
            if (StringUtils.isBlank(n.id()) || StringUtils.isBlank(n.name())) return false;
            if (StringUtils.isBlank(n.doneWhen())) return false;
            for (String d : n.dependsOn())
                if (g.byId(d) == null) return false;
        }
        return true;
    }

    TaskGraph templateGraph(String userMessage, TaskPlan plan) {
        if (looksLikeFetchWrite(userMessage, plan))
            return fetchWriteTemplate();
        List<TaskNode> nodes = new ArrayList<>();
        if (plan != null && plan.steps() != null && !plan.steps().isEmpty()) {
            String prev = null;
            int i = 0;
            for (TaskStep step : plan.steps()) {
                if (step == null || StringUtils.isBlank(step.goal())) continue;
                i++;
                String id = "n" + i;
                List<String> deps = prev == null ? List.of() : List.of(prev);
                String cap = guessCapability(step.goal(), plan);
                nodes.add(new TaskNode(id, step.goal().trim(), cap, deps,
                        TaskNodeStatus.PENDING, 10 - i, "note_required", "", "", 0));
                prev = id;
            }
        }
        while (nodes.size() < 3) {
            int i = nodes.size() + 1;
            String id = "n" + i;
            List<String> deps = nodes.isEmpty() ? List.of()
                    : List.of(nodes.get(nodes.size() - 1).id());
            String name = switch (i) {
                case 1 -> "澄清目标与约束：" + abbreviate(userMessage, 40);
                case 2 -> "执行核心工作：" + abbreviate(
                        plan != null ? plan.taskGoal() : userMessage, 40);
                default -> "验收与交付";
            };
            String cap = i == 3 ? "deliver" : guessCapability(name, plan);
            nodes.add(new TaskNode(id, name, cap, deps, TaskNodeStatus.PENDING,
                    10 - i, "note_required", "", "", 0));
        }
        return new TaskGraph(nodes);
    }

    static TaskGraph fetchWriteTemplate() {
        return new TaskGraph(List.of(
                new TaskNode("n1", "打开链接提取正文并写入 md", "browser",
                        List.of(), TaskNodeStatus.PENDING, 10,
                        "file_exists:output/notes.md", "browser_navigate", "", 0)));
    }

    static int minNodes(TaskGraph g) {
        return graphHasFetchAndWrite(g) ? 1 : 3;
    }

    static boolean graphHasFetchAndWrite(TaskGraph g) {
        if (g == null) return false;
        boolean fetch = false;
        boolean write = false;
        for (TaskNode n : g.nodes()) {
            String c = n.capability().toLowerCase();
            String name = n.name().toLowerCase();
            if (c.contains("browser") || c.contains("web")
                    || name.contains("打开") || name.contains("飞书")
                    || name.contains("网页"))
                fetch = true;
            if (c.contains("write") || c.contains("deliver")
                    || name.contains("写") || name.contains("md"))
                write = true;
        }
        return fetch && write;
    }

    static boolean looksLikeFetchWrite(String userMessage, TaskPlan plan) {
        String t = ((userMessage == null ? "" : userMessage) + " "
                + (plan == null || plan.taskGoal() == null ? "" : plan.taskGoal()))
                .toLowerCase();
        boolean fetch = t.contains("http") || t.contains("feishu") || t.contains("wiki")
                || t.contains("飞书") || t.contains("网页");
        boolean write = t.contains(".md") || t.contains("写入") || t.contains("markdown")
                || t.contains("写文件") || t.contains("学习资料")
                || (plan != null && plan.intent() == IntentType.FILE_DELIVERY);
        return fetch && write;
    }

    private static String guessCapability(String text, TaskPlan plan) {
        String t = text == null ? "" : text.toLowerCase();
        if (plan != null && plan.intent() == IntentType.IMAGE_GENERATION) return "image";
        if (looksLikeBrowser(t)) return "browser";
        if (plan != null && plan.intent() == IntentType.RESEARCH) return "research";
        if (plan != null && plan.intent() == IntentType.FILE_DELIVERY) return "file_write";
        if (t.contains("图") || t.contains("画") || t.contains("image")) return "image";
        if (t.contains("搜索") || t.contains("调研") || t.contains("网页")) return "web";
        if (t.contains("代码") || t.contains("实现") || t.contains("refactor")) return "code";
        if (t.contains("写") || t.contains("文件") || t.contains("文档") || t.contains("md"))
            return "file_write";
        if (t.contains("浏览器") || t.contains("打开")) return "browser";
        return "general";
    }

    private static boolean looksLikeBrowser(String t) {
        return t.contains("http") || t.contains("feishu") || t.contains("wiki")
                || t.contains("密码") || t.contains("飞书")
                || t.contains("浏览器") || t.contains("打开链接")
                || t.contains("打开网页");
    }

    private static TaskGraph markPending(TaskGraph g) {
        List<TaskNode> list = new ArrayList<>();
        for (TaskNode n : g.nodes())
            list.add(n.withStatus(TaskNodeStatus.PENDING));
        return new TaskGraph(list);
    }

    private static String extractJson(String text) {
        if (text == null) return "{}";
        String t = text.trim();
        int start = t.indexOf('{');
        int end = t.lastIndexOf('}');
        if (start >= 0 && end > start) return t.substring(start, end + 1);
        return t;
    }

    private static String textOr(JsonNode n, String field, String def) {
        if (n == null || !n.has(field) || n.get(field).isNull()) return def;
        String v = n.get(field).asText();
        return StringUtils.isBlank(v) ? def : v;
    }

    private static String abbreviate(String s, int max) {
        if (s == null) return "";
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }
}
