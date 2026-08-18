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
 * Task Compiler：NL + Intent → Goal + TaskGraph。只产出图，不验收。
 */
@Component
public class GoalCompiler {

    private static final Logger log = LoggerFactory.getLogger(GoalCompiler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    static final String ARTIFACT_SOURCE = "source_text";
    static final String ARTIFACT_NOTES = "notes_md";
    static final String SOURCE_FILE = "_source.md";
    static final String NOTES_FILE = "notes.md";

    private static final String COMPILER_SYSTEM = """
            你是任务图编译器。把用户目标编译成可执行、可验收、无环的 DAG。
            只输出 JSON。不执行任务，不编造工具，不预写失败重试路径。
            {
              "objective":"...",
              "constraints":["..."],
              "successCriteria":["..."],
              "entities":{"k":"v"},
              "nodes":[
                {"id":"n1","name":"...","capability":"...",
                 "dependsOn":[],"inputs":[],"outputs":[],"priority":50,
                 "doneWhen":{"type":"note_required","path":"","criteria":""},
                 "toolHint":""}
              ]
            }
            capability 取值：file_write / web / code / image / browser / shell /
            research / deliver / plan / general。
            doneWhen.type 仅：note_required / file_exists / media_delivered / llm_judge /
            command_success / validation_passed。
            file_exists 填 path，可加 criteria 做内容验收。
            llm_judge、validation_passed 可填 criteria。
            command_success 要求 evidence 含 exit_code=0。
            规则：
            有独立验收的步骤必须拆开。不要为凑数拆节点。
            复杂任务通常 ≥3 个有独立价值的节点；凑不出就不要拆。
            问答/单步可 1 节点。依赖无环；id 唯一。
            不要创建接收请求、理解需求、开始/结束等无执行价值的节点。
            生成与交付仅在验收标准不同时拆开。
            dependsOn 只表示执行前置；无依赖的节点可并行。
            后续节点的 inputs 必须引用前置节点的 outputs。
            不要制造无意义的 input/output。
            每节点必须有 doneWhen，按本步如何证明完成来选，不要默认落盘。
            capability 是能力类别，不决定具体工具；执行阶段按能力与上下文路由。
            toolHint 只是路由候选，不是强制。不确定就留空。填写必须是已注册工具名。
            priority 为 0~100 的整数，越大越优先，只影响同批 READY 调度。
            """;

    private final PlannerProperties properties;

    public GoalCompiler(PlannerProperties properties) {
        this.properties = properties;
    }

    public record CompileResult(Goal goal, TaskGraph graph, boolean fromTemplate) {}

    public CompileResult compile(ChatModel chat, String userMessage, TaskPlan plan) {
        Goal base = goalFromPlan(userMessage, plan);
        if (looksLikeFetchWrite(userMessage, plan)) {
            log.info("GoalCompiler 使用读链写文件模板 nodes=3");
            return new CompileResult(base, markPending(fetchWriteTemplate()), true);
        }
        if (plan == null || !plan.requiresStructuredPlan()) {
            return fallback(base, userMessage, plan);
        }
        int retries = Math.max(0, properties.getCompilerRetry());
        for (int i = 0; i <= retries; i++) {
            try {
                TaskGraph g = compileWithLlm(chat, userMessage, plan);
                if (g != null && !g.isEmpty()) {
                    Goal goal = new Goal(base.goalId(), base.objective(), base.intent(),
                            base.entities(), base.constraints(), base.successCriteria());
                    return new CompileResult(goal, markPending(g), false);
                }
            } catch (Exception e) {
                log.warn("GoalCompiler LLM 拆解失败 retry={}: {}", i, e.getMessage());
            }
        }
        return fallback(base, userMessage, plan);
    }

    public CompileResult fallback(Goal base, String userMessage, TaskPlan plan) {
        Goal goal = base != null ? base : goalFromPlan(userMessage, plan);
        TaskGraph graph = markPending(templateGraph(userMessage, plan));
        log.info("GoalCompiler 使用模板图 nodes={}", graph.nodes().size());
        return new CompileResult(goal, graph, true);
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
            int priority = n.has("priority") ? n.get("priority").asInt(50) : 50;
            if (priority < 0) priority = 0;
            if (priority > 100) priority = 100;
            String toolHint = textOr(n, "toolHint", textOr(n, "tool_hint", ""));
            JsonNode dwNode = n.get("doneWhen");
            if (dwNode == null) dwNode = n.get("done_when");
            list.add(new TaskNode(id, name, cap, deps, stringList(n, "inputs"),
                    stringList(n, "outputs"), TaskNodeStatus.PENDING,
                    priority, DoneWhen.parse(dwNode), toolHint, "", 0, ""));
        }
        return new TaskGraph(list);
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
                        List.of(), List.of(), TaskNodeStatus.PENDING, 10 - i,
                        DoneWhen.note(), "", "", 0, ""));
                prev = id;
            }
        }
        if (nodes.isEmpty()) {
            String raw = plan != null && StringUtils.isNotBlank(plan.taskGoal())
                    ? plan.taskGoal() : userMessage;
            String name = abbreviate(raw, 40);
            if (StringUtils.isBlank(name)) {
                name = "执行任务";
            }
            String cap = guessCapability(name, plan);
            nodes.add(new TaskNode("n1", name, cap, List.of(), List.of(), List.of(),
                    TaskNodeStatus.PENDING, 10, DoneWhen.note(), "", "", 0, ""));
        }
        return new TaskGraph(nodes);
    }

    static TaskGraph fetchWriteTemplate() {
        return new TaskGraph(List.of(
                new TaskNode("n1",
                        "打开页面后 browser_extract_text 抽完全部章节到 " + SOURCE_FILE,
                        "browser", List.of(), List.of(), List.of(ARTIFACT_SOURCE),
                        TaskNodeStatus.PENDING, 10,
                        DoneWhen.file(SOURCE_FILE),
                        "browser_extract_text", "", 0, ""),
                new TaskNode("n2", "读取临时文件，按真实章节写入学习文档",
                        "file_write", List.of("n1"), List.of(ARTIFACT_SOURCE),
                        List.of(ARTIFACT_NOTES), TaskNodeStatus.PENDING, 9,
                        DoneWhen.file(NOTES_FILE), "write_file", "", 0, ""),
                new TaskNode("n3", "对照临时文件校验学习文档是否写全",
                        "deliver", List.of("n1", "n2"),
                        List.of(ARTIFACT_SOURCE, ARTIFACT_NOTES), List.of(),
                        TaskNodeStatus.PENDING, 8,
                        DoneWhen.judge("学习文档须覆盖临时文件全部章节且无大段缺失"),
                        "read_file", "", 0, "")));
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
        if (t.contains("发布") || t.contains("草稿") || t.contains("publish")
                || t.contains("draft")) {
            return "web";
        }
        if (plan != null && plan.intent() == IntentType.FILE_DELIVERY) return "file_write";
        if (t.contains("图") || t.contains("画") || t.contains("image")) return "image";
        if (t.contains("搜索") || t.contains("调研") || t.contains("网页")) return "web";
        if (t.contains("代码") || t.contains("实现") || t.contains("refactor")) return "code";
        if (t.contains("写") || t.contains("文件") || t.contains("文档") || t.contains("md"))
            return "file_write";
        if (t.contains("浏览器") || t.contains("打开")) return "browser";
        return "general";
    }

    private static List<String> stringList(JsonNode n, String field) {
        JsonNode a = n == null ? null : n.get(field);
        if (a == null || !a.isArray()) return List.of();
        List<String> out = new ArrayList<>();
        for (JsonNode x : a) {
            String s = x.asText();
            if (StringUtils.isNotBlank(s)) out.add(s.trim());
        }
        return out;
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
