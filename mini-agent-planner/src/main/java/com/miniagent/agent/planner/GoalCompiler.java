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
    static final String DIAGRAM_MMD = "architecture.mmd";
    static final String DIAGRAM_PNG = "architecture.png";

    /**
     * 告诉模型的 capability 词表。必须与 ToolCapabilityIndex 的 key 保持一致，
     * 否则编译出的节点会在硬闸门下拿不到工具面。由 ToolCapabilityIndex.selfCheck 校验。
     */
    static final List<String> CAPABILITIES = List.of(
            "file_write", "web", "code", "image", "browser", "shell",
            "research", "deliver", "plan", "general");

    private static final String COMPILER_SYSTEM = """
            你是任务图编译器。把用户目标编译成可执行、可验收、无环的 DAG。
            只输出 JSON。不执行任务，不编造工具，不预写失败重试路径。

            【核心规则：用户意图优先级】
            用户意图的判断依据（按优先级从高到低）：
            1. 用户的文字描述 > 附件内容（绝对优先）
            2. 如果用户只上传了文件，没有文字描述 → 任务类型由文件内容决定
            3. 如果用户上传了文件，同时有文字描述 → 以文字描述为准，文件只是源内容输入

            适用范围：不管产出物是什么类型（图片、视频、语音、文档、代码、...），
            只要用户有文字描述，就必须以文字描述为依据拆解任务！

            文字描述中的关键词提取：
            - 动词决定任务类型：生成/创建/写/分析/搜索/转换/美化/...
            - 名词决定产出物：图片/图表/视频/语音/文档/代码/报告/...
            - 形容词决定标准：美观/详细/简洁/专业/...

            常见错误：
            - 看到附件就进入"分析文档"模式，忽略用户文字描述
            - 看到图片附件就认为是"图片识别"任务，忽略用户要的是"生成图片"
            - 看到视频附件就认为是"视频分析"任务，忽略用户要的是"生成视频"
            附件只是源内容，不是任务类型定义！

            【第一步：识别任务类型】
            根据用户文字描述（优先）或文件内容（仅无文字时），判断用户要的最终产出物：
            - 图片生成：用户要生成/创建/美化图片（架构图、流程图、海报、设计图等）
            - 视频生成：用户要生成/编辑/转换视频
            - 语音生成：用户要生成/转换语音
            - 文档生成：用户要写文档、报告、方案
            - 代码生成：用户要写代码、脚本、程序
            - 数据分析：用户要分析数据、统计、可视化
            - 信息检索：用户要搜索、查找、了解信息
            - 文件操作：用户要读写、修改、整理文件
            - 问答对话：用户要解释、说明、回答问题

            【第二步：按类型选择拆解策略】
            图表生成类任务的正确流程：
            1. 读取源内容（文档/数据/需求）
            2. 提取关键信息（结构、模块、流程）
            3. 生成图表代码（SVG/Mermaid/HTML）
            4. 验证图表内容
            注意：不要把图表生成拆成"分析报告"！

            文档/报告类任务的流程：
            1. 读取源内容
            2. 提取关键信息
            3. 分析/评估
            4. 生成文档
            5. 整合输出

            【第三步：输出 JSON】
            {
              "taskType": "图表生成/文档生成/代码生成/...",
              "objective": "...",
              "constraints": ["..."],
              "successCriteria": ["..."],
              "entities": {"k": "v"},
              "nodes": [
                {"id": "n1", "name": "...", "capability": "...",
                 "dependsOn": [], "inputs": [], "outputs": [], "priority": 50,
                 "doneWhen": {"type": "note_required", "path": "", "criteria": ""},
                 "toolHint": ""}
              ]
            }

            capability 取值：file_write / web / code / image / browser / shell /
            research / deliver / plan / general。
            写代码/改代码用 code；写文档、报告落盘用 file_write；
            架构图/流程图用 image：先 write_file 写 .mmd，再 render_diagram 产出 .png，
            不要只交 Mermaid 源码；最终交付物用 deliver；跑命令、编译、测试用 shell。
            涉及网页的任务按用户真实动作拆：要整篇正文（飞书/wiki 等长文）就用 browser_extract_text
            一次抽全再落盘；要点击、填表、读取局部字段，就拆成 navigate → snapshot → click/type
            这类交互步骤，不要一律套「抽正文写文档」。
            doneWhen.type 仅：note_required / file_exists / media_delivered / llm_judge /
            command_success / validation_passed。
            file_exists 填 path，可加 criteria 做内容验收。
            llm_judge、validation_passed 可填 criteria。
            command_success 要求 evidence 含 exit_code=0。

            【规则】
            有独立验收的步骤必须拆开。不要为凑数拆节点。
            复杂任务通常 ≥3 个有独立价值的节点；凑不出就不要拆。
            问答/单步可 1 节点。依赖无环；id 唯一。
            不要创建接收请求、理解需求、开始/结束等无执行价值的节点。
            生成与交付仅在验收标准不同时拆开。
            dependsOn 只表示执行前置；无依赖的节点可并行。
            后续节点的 inputs 必须引用前置节点的 outputs。
            不要制造无意义的 input/output。
            每节点必须有 doneWhen，按本步如何证明完成来选。
            凡是产出文件的节点（代码、文档、报告、图表、数据），doneWhen 必须用
            file_exists 并填具体 path，outputs 不能为空；只有产出结论/信息、
            本身不落盘的节点才允许 note_required。
            path 必须是 workspace 相对文件名（如 news.json），禁止 /tmp 或盘符绝对路径。
            note_required 只靠一段文字就算通过，用错会让"什么都没交付"被判成功。
            capability 是能力类别，不决定具体工具；执行阶段按能力与上下文路由。
            toolHint 只是路由候选，不是强制。不确定就留空。填写必须是已注册工具名。
            priority 为 0~100 的整数，越大越优先，只影响同批 READY 调度。
            """;

    private final PlannerProperties properties;

    public GoalCompiler(PlannerProperties properties) {
        this.properties = properties;
    }

    public record CompileResult(Goal goal, TaskGraph graph, boolean fromTemplate) {}
    public record ParsedCompilation(Goal goal, TaskGraph graph) {}

    /**
     * 带修正提示的编译方法，用于 replan 闭环
     * @param chat 聊天模型
     * @param userMessage 用户消息
     * @param plan 任务计划
     * @param correctionPrompt 验证失败的修正提示
     * @return 编译结果
     */
    public CompileResult compileWithCorrection(ChatModel chat, String userMessage,
                                               TaskPlan plan, String correctionPrompt) {
        Goal base = goalFromPlan(userMessage, plan);

        // 如果有修正提示，尝试使用 LLM 重新编译
        if (chat != null && StringUtils.isNotBlank(correctionPrompt)) {
            try {
                ParsedCompilation parsed = compileWithLlmAndCorrection(
                    chat, userMessage, plan, correctionPrompt);
                if (parsed != null && parsed.graph() != null && !parsed.graph().isEmpty()) {
                    return new CompileResult(parsed.goal(), markPending(parsed.graph()), false);
                }
            } catch (Exception e) {
                log.warn("GoalCompiler 带修正的 LLM 编译失败: {}", e.getMessage());
            }
        }

        // 如果修正编译失败，使用 fallback
        return fallback(base, userMessage, plan);
    }

    /**
     * 带修正提示的 LLM 编译
     */
    private ParsedCompilation compileWithLlmAndCorrection(ChatModel chat, String userMessage,
                                                          TaskPlan plan, String correctionPrompt) {
        if (chat == null) {
            return null;
        }

        String user = "用户消息:\n" + userMessage
                + "\n\n意图:" + (plan == null ? "UNKNOWN" : plan.intent())
                + "\n任务目标:" + (plan == null ? "" : plan.taskGoal())
                + "\n\n**修正要求**:\n" + correctionPrompt
                + "\n\n请根据上述修正要求重新生成任务图。";

        var response = chat.chat(ChatRequest.builder()
                .messages(List.of(new SystemMessage(COMPILER_SYSTEM), UserMessage.from(user)))
                .build());
        String text = response.aiMessage() == null ? "" : response.aiMessage().text();
        try {
            return parseCompilation(text, goalFromPlan(userMessage, plan));
        } catch (Exception e) {
            throw new IllegalStateException("parse graph failed: " + e.getMessage(), e);
        }
    }

    public CompileResult compile(ChatModel chat, String userMessage, TaskPlan plan) {
        Goal base = goalFromPlan(userMessage, plan);
        if (looksLikeDiagram(userMessage, plan)) {
            log.info("GoalCompiler 使用出图模板 nodes=2");
            return new CompileResult(base, markPending(diagramTemplate()), true);
        }
        // 抓网页写文档模板不再抢在 LLM 前面：带链接又提到 .md 时，用户可能是填表/抽字段。
        // 该模板只留在 fallback 的 templateGraph 里。
        if (plan == null || !plan.requiresStructuredPlan()) {
            return fallback(base, userMessage, plan);
        }
        int retries = Math.max(0, properties.getCompilerRetry());
        for (int i = 0; i <= retries; i++) {
            try {
                ParsedCompilation parsed = compileWithLlm(chat, userMessage, plan);
                if (parsed != null && parsed.graph() != null && !parsed.graph().isEmpty()) {
                    return new CompileResult(parsed.goal(), markPending(parsed.graph()), false);
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
                if (s != null && StringUtils.isNotBlank(s.goal())) {
                    criteria.add(s.goal());
                }
        if (criteria.isEmpty()) {
            criteria.add("完成 objective 且可验收");
        }
        return new Goal(
                "goal_" + UUID.randomUUID().toString().substring(0, 8),
                objective, intent, Map.of(), List.of(), criteria);
    }

    private ParsedCompilation compileWithLlm(ChatModel chat, String userMessage, TaskPlan plan) {
        if (chat == null) {
            return null;
        }
        String user = "用户消息:\n" + userMessage
                + "\n\n意图:" + (plan == null ? "UNKNOWN" : plan.intent())
                + "\n任务目标:" + (plan == null ? "" : plan.taskGoal());
        var response = chat.chat(ChatRequest.builder()
                .messages(List.of(new SystemMessage(COMPILER_SYSTEM), UserMessage.from(user)))
                .build());
        String text = response.aiMessage() == null ? "" : response.aiMessage().text();
        try {
            return parseCompilation(text, goalFromPlan(userMessage, plan));
        } catch (Exception e) {
            throw new IllegalStateException("parse graph failed: " + e.getMessage(), e);
        }
    }

    TaskGraph parseGraph(String text) throws Exception {
        String json = extractJson(text);
        JsonNode root = MAPPER.readTree(json);
        JsonNode nodes = root.get("nodes");
        if (nodes == null || !nodes.isArray()) {
            return new TaskGraph(List.of());
        }
        List<TaskNode> list = new ArrayList<>();
        for (JsonNode n : nodes) {
            String id = textOr(n, "id", "n" + (list.size() + 1));
            String name = textOr(n, "name", id);
            String cap = textOr(n, "capability", "general");
            List<String> deps = new ArrayList<>();
            JsonNode d = n.get("dependsOn");
            if (d == null) {
                d = n.get("depends_on");
            }
            if (d != null && d.isArray())
                for (JsonNode x : d) {
                    deps.add(x.asText());
                }
            int priority = n.has("priority") ? n.get("priority").asInt(50) : 50;
            if (priority < 0) {
                priority = 0;
            }
            if (priority > 100) {
                priority = 100;
            }
            String toolHint = textOr(n, "toolHint", textOr(n, "tool_hint", ""));
            JsonNode dwNode = n.get("doneWhen");
            if (dwNode == null) {
                dwNode = n.get("done_when");
            }
            list.add(new TaskNode(id, name, cap, deps, stringList(n, "inputs"),
                    stringList(n, "outputs"), TaskNodeStatus.PENDING,
                    priority, DoneWhen.parse(dwNode), toolHint, "", 0, ""));
        }
        return new TaskGraph(list);
    }

    /** 解析模型完整编译结果，同时保留 Goal 根字段而非丢回 TaskPlan。 */
    ParsedCompilation parseCompilation(String text, Goal base) throws Exception {
        JsonNode root = MAPPER.readTree(extractJson(text));
        TaskGraph graph = parseGraph(text);
        Goal seed = base == null
                ? new Goal("goal_" + UUID.randomUUID().toString().substring(0, 8), "", "UNKNOWN",
                null, Map.of(), List.of(), List.of())
                : base;
        String objective = textOr(root, "objective", seed.objective());
        String intent = textOr(root, "intent", seed.intent());
        String taskType = textOr(root, "taskType", seed.taskType());
        Map<String, String> entities = stringMap(root.get("entities"), seed.entities());
        List<String> constraints = stringList(root, "constraints");
        if (constraints.isEmpty()) constraints = seed.constraints();
        List<String> criteria = stringList(root, "successCriteria");
        if (criteria.isEmpty()) criteria = stringList(root, "success_criteria");
        if (criteria.isEmpty()) criteria = seed.successCriteria();
        Goal goal = new Goal(seed.goalId(), objective, intent, taskType, entities, constraints, criteria);
        return new ParsedCompilation(goal, graph);
    }

    TaskGraph templateGraph(String userMessage, TaskPlan plan) {
        if (looksLikeDiagram(userMessage, plan)) {
            return diagramTemplate();
        }
        if (looksLikeFetchWrite(userMessage, plan))
            return fetchWriteTemplate();
        List<TaskNode> nodes = new ArrayList<>();
        if (plan != null && plan.steps() != null && !plan.steps().isEmpty()) {
            String prev = null;
            int i = 0;
            for (TaskStep step : plan.steps()) {
                if (step == null || StringUtils.isBlank(step.goal())) {
                    continue;
                }
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

    static TaskGraph diagramTemplate() {
        return new TaskGraph(List.of(
                new TaskNode("n1",
                        "把架构写成 Mermaid 写入 " + DIAGRAM_MMD,
                        "image", List.of(), List.of(), List.of("diagram_mmd"),
                        TaskNodeStatus.PENDING, 10,
                        DoneWhen.file(DIAGRAM_MMD), "write_file", "", 0, ""),
                new TaskNode("n2",
                        "调用 render_diagram 把 " + DIAGRAM_MMD + " 渲染成 " + DIAGRAM_PNG,
                        "image", List.of("n1"), List.of("diagram_mmd"), List.of("diagram_png"),
                        TaskNodeStatus.PENDING, 9,
                        DoneWhen.file(DIAGRAM_PNG), "render_diagram", "", 0, "")));
    }

    static boolean looksLikeDiagram(String userMessage, TaskPlan plan) {
        String t = ((userMessage == null ? "" : userMessage) + " "
                + (plan == null || plan.taskGoal() == null ? "" : plan.taskGoal()))
                .toLowerCase();
        return t.contains("架构图") || t.contains("流程图") || t.contains("时序图")
                || t.contains("结构图") || t.contains("mermaid") || t.contains(".mmd")
                || t.contains("render_diagram") || t.contains("出设计图")
                || t.contains("architecture.png") || t.contains("architecture.mmd");
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
        // 必须是真链接：裸 "http" 会把 http.server / http_get 这类词误判成抓网页，
        // 而本方法命中就会在 LLM 编译之前强制套用 3 节点浏览器模板。
        boolean fetch = t.contains("http://") || t.contains("https://")
                || t.contains("feishu") || t.contains("wiki")
                || t.contains("飞书") || t.contains("网页");
        boolean write = t.contains(".md") || t.contains("写入") || t.contains("markdown")
                || t.contains("写文件") || t.contains("学习资料")
                || (plan != null && plan.intent() == IntentType.FILE_DELIVERY);
        return fetch && write;
    }

    private static String guessCapability(String text, TaskPlan plan) {
        String t = text == null ? "" : text.toLowerCase();
        if (plan != null && plan.intent() == IntentType.IMAGE_GENERATION) {
            return "image";
        }
        if (looksLikeBrowser(t)) {
            return "browser";
        }
        if (plan != null && plan.intent() == IntentType.RESEARCH) {
            return "research";
        }
        if (t.contains("发布") || t.contains("草稿") || t.contains("publish")
                || t.contains("draft")) {
            return "web";
        }
        if (plan != null && plan.intent() == IntentType.FILE_DELIVERY) {
            return "file_write";
        }
        if (t.contains("图") || t.contains("画") || t.contains("image")) {
            return "image";
        }
        if (t.contains("搜索") || t.contains("调研") || t.contains("网页")) {
            return "web";
        }
        if (t.contains("代码") || t.contains("实现") || t.contains("refactor")) {
            return "code";
        }
        if (t.contains("写") || t.contains("文件") || t.contains("文档") || t.contains("md"))
            return "file_write";
        if (t.contains("浏览器") || t.contains("打开")) {
            return "browser";
        }
        return "general";
    }

    private static List<String> stringList(JsonNode n, String field) {
        JsonNode a = n == null ? null : n.get(field);
        if (a == null || !a.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (JsonNode x : a) {
            String s = x.asText();
            if (StringUtils.isNotBlank(s)) {
                out.add(s.trim());
            }
        }
        return out;
    }

    private static Map<String, String> stringMap(JsonNode node, Map<String, String> fallback) {
        if (node == null || !node.isObject()) return fallback == null ? Map.of() : fallback;
        Map<String, String> out = new java.util.LinkedHashMap<>();
        node.fields().forEachRemaining(e -> {
            if (e.getValue() != null && !e.getValue().isNull()) {
                out.put(e.getKey(), e.getValue().asText());
            }
        });
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
        if (text == null) {
            return "{}";
        }
        String t = text.trim();
        int start = t.indexOf('{');
        int end = t.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return t.substring(start, end + 1);
        }
        return t;
    }

    private static String textOr(JsonNode n, String field, String def) {
        if (n == null || !n.has(field) || n.get(field).isNull()) {
            return def;
        }
        String v = n.get(field).asText();
        return StringUtils.isBlank(v) ? def : v;
    }

    private static String abbreviate(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }
}
