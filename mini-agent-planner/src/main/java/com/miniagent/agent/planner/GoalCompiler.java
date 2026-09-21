package com.miniagent.agent.planner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.agent.task.TaskPlan;
import com.miniagent.agent.task.TaskSignals;
import com.miniagent.agent.task.TaskStep;
import com.miniagent.agent.llm.DedicatedChatModel;
import com.miniagent.agent.tool.CapabilityRegistry;
import com.miniagent.common.model.EffectiveModelContext;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import jakarta.annotation.PostConstruct;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Task Compiler：NL + 任务信号 → Goal + TaskGraph。只产出图，不验收。
 */
@Component
public class GoalCompiler {

    private static final Logger log = LoggerFactory.getLogger(GoalCompiler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    static final String ARTIFACT_SOURCE = "source_text";
    static final String ARTIFACT_NOTES = "notes_md";
    static final String NOTES_FILE = "notes.md";
    static final String DIAGRAM_MMD = "architecture.mmd";
    static final String DIAGRAM_PNG = "architecture.png";
    static final String ARTIFACT_TABLE = "table_data";
    static final String ARTIFACT_STATS = "stats";
    static final String CAP_PLAN = "plan";
    static final String CAP_WEB = "web";
    static final String CLARIFY_HINT = "请补充：交付文件名或数据来源（URL）";

    /**
     * 编译器系统提示。节点 capability 禁止 general；词表见
     * {@link com.miniagent.agent.tool.CapabilityRegistry#PLANNER_CAPABILITIES}。
     */
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

            【第二步：按产出物拆节点，不按动作拆】
            先写清最终交付物，再写中间能独立验收的产物。每个节点只交一件东西。
            禁止「收集资料 / 分析 / 写报告」这种只有过程没有产物的节点。
            例：竞品分析 → 竞品清单 → 功能对比表 → 价格表 → 建议
            （各有 outputs 与 doneWhen）。
            例：查文档并保存 → 资料原文(web, note_required)
            → 落盘文件(file_write, file_exists)。
            例：读表再统计 → 表格数据(file_read) → 统计结论(code, llm_judge)。
            图表：.mmd 源码与 .png 渲染是两个产物，不要拆成「分析然后画图」。

            【第三步：输出 JSON】
            {
              "taskType": "图表生成/文档生成/代码生成/...",
              "objective": "...",
              "constraints": ["..."],
              "successCriteria": ["notes.md"],
              "entities": {"k": "v"},
              "nodes": [
                {"id": "n1", "name": "...", "capability": "...",
                 "dependsOn": [], "inputs": [], "outputs": [], "priority": 50,
                 "doneWhen": {"type": "note_required", "path": "", "criteria": ""}}
              ]
            }

            successCriteria 只填可验收项：workspace 相对文件名，或与某节点
            doneWhen.criteria 完全相同的评判句。禁止「完成 objective 且可验收」。

            capability 取值：file_read / file_write / web / code / image / browser /
            shell / research / deliver / plan。禁止 general：调度器无法从它判断
            工具面和验收，归不了类就拆成有明确能力的节点。
            读表格/已有文件用 file_read；写文档、报告落盘用 file_write；
            写代码/改代码用 code；
            架构图/流程图用 image：先产出 .mmd 再渲染 .png，不要只交 Mermaid 源码；
            最终交付物用 deliver；跑命令、编译、测试用 shell。
            涉及网页的任务按用户真实动作拆：要整篇正文（飞书/wiki 等长文）就用
            browser 一次抽全再落盘；要点击、填表、读取局部字段，就拆成打开页面 →
            看结构 → 交互，不要一律套「抽正文写文档」。
            doneWhen.type 仅：note_required / file_exists / media_delivered / llm_judge /
            command_success / validation_passed。
            file_exists 填 path，可加 criteria 做内容验收。
            llm_judge、validation_passed 可填 criteria。
            command_success 要求 evidence 含 exit_code=0。

            【节点粒度】
            Node = 单一主要产出、单一主要能力、可独立执行/重试/验收的原子任务。
            不是用户的一句话业务目标。禁止把「我要完成什么」直接编成一个节点。
            必须拆：多个独立产出；后一步消费前一步结果；能力切换（如 web→file_write、
            file_read→code）；中间产物要给后续节点用；某步需独立验收或人工确认。
            例：查文档并保存 Markdown → 获取资料(web) → 写入文件(file_write)，两节点即可。
            例：读 Excel 再统计销售额 → 读取(file_read) → 统计(code)。
            不要拆：打开/读取/解析同一 PDF 这类工具内部步骤；不要为凑节点而拆。
            拆分依据是执行依赖和原子性，不是句子长短，也不是「然后」这个词。
            一个业务目标可以对应多个执行节点。

            【规则】
            有独立验收的步骤必须拆开。不要为凑数拆节点。
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
            capability 是能力类别，不决定具体工具；执行阶段由 Router 按能力从注册表
            检索候选，模型在候选内选择。不要填写具体工具名。
            priority 为 0~100 的整数，越大越优先，只影响同批 READY 调度。
            """;

    private final PlannerProperties properties;
    private volatile ChatModel dedicatedPlanner;

    public GoalCompiler(PlannerProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void initDedicatedPlanner() {
        dedicatedPlanner = DedicatedChatModel.openAiOrNull(
                properties.getPlannerModelName(), properties.getPlannerBaseUrl(),
                properties.getPlannerApiKey(), properties.getPlannerTimeoutSeconds());
        if (dedicatedPlanner != null) {
            log.info("GoalCompiler 专用规划模型已就绪: model={}",
                    properties.getPlannerModelName());
        } else {
            log.info("GoalCompiler 未配置专用规划模型，编译走主对话模型");
        }
    }

    /**
     * 专用规划模型优先；未配置专用模型时跟随本轮生效模型（调用方通常已传用户模型，
     * 这里再兜一层 {@link EffectiveModelContext} 以防调用方传的是全局 {@code @Primary} Bean）。
     */
    ChatModel resolvePlannerChat(ChatModel fallback) {
        return dedicatedPlanner != null ? dedicatedPlanner : EffectiveModelContext.chatOr(fallback);
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
        ChatModel planner = resolvePlannerChat(chat);
        if (planner != null && StringUtils.isNotBlank(correctionPrompt)) {
            try {
                ParsedCompilation parsed = compileWithLlmAndCorrection(
                    planner, userMessage, plan, correctionPrompt);
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
                + "\n\n命中信号:" + signalsOf(plan)
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
        CompileResult structured = fallback(base, userMessage, plan);
        if (structureDeterminate(structured.graph(), userMessage, plan)) {
            log.info("GoalCompiler 结构确定 nodes={} clarify={}",
                    structured.graph().nodes().size(), structured.goal().isClarify());
            return structured;
        }
        ChatModel planner = resolvePlannerChat(chat);
        if (planner != null && (plan == null || plan.requiresStructuredPlan())) {
            int retries = Math.max(0, properties.getCompilerRetry());
            for (int i = 0; i <= retries; i++) {
                try {
                    ParsedCompilation parsed = compileWithLlm(planner, userMessage, plan);
                    if (parsed != null && parsed.graph() != null && !parsed.graph().isEmpty()) {
                        return new CompileResult(
                                parsed.goal(), markPending(parsed.graph()), false);
                    }
                } catch (Exception e) {
                    log.warn("GoalCompiler LLM 拆解失败 retry={}: {}", i, e.getMessage());
                }
            }
        }
        return structured;
    }

    public CompileResult fallback(Goal base, String userMessage, TaskPlan plan) {
        Goal raw = base != null ? base : goalFromPlan(userMessage, plan);
        TaskGraph graph = markPending(templateGraph(userMessage, plan));
        String taskType = isClarifyGraph(graph)
                ? Goal.TASK_TYPE_CLARIFY : raw.taskType();
        Goal goal = new Goal(raw.goalId(), raw.objective(), raw.signals(), taskType,
                raw.entities(), raw.constraints(),
                checkableCriteria(List.of(), raw.objective(), graph));
        log.info("GoalCompiler 使用模板图 nodes={} clarify={}",
                graph.nodes().size(), goal.isClarify());
        return new CompileResult(goal, graph, true);
    }

    private Goal goalFromPlan(String userMessage, TaskPlan plan) {
        String objective = plan != null && StringUtils.isNotBlank(plan.taskGoal())
                ? plan.taskGoal() : (userMessage == null ? "" : userMessage);
        return new Goal(
                "goal_" + UUID.randomUUID().toString().substring(0, 8),
                objective, signalsOf(plan), Map.of(), List.of(),
                checkableCriteria(List.of(), objective, null));
    }

    /** 命中信号清单，只作提示与审计；判断逻辑一律直接读 {@link TaskSignals} 的字段。 */
    private static String signalsOf(TaskPlan plan) {
        return plan == null ? TaskSignals.NONE.describe() : plan.signals().describe();
    }

    private ParsedCompilation compileWithLlm(ChatModel chat, String userMessage, TaskPlan plan) {
        if (chat == null) {
            return null;
        }
        String user = "用户消息:\n" + userMessage
                + "\n\n命中信号:" + signalsOf(plan)
                + "\n任务目标:" + (plan == null ? "" : plan.taskGoal())
                + "\n可用能力: file_read / file_write / web / code / image / browser"
                + " / shell / research / deliver / plan"
                + "\n禁止 general。"
                + "\n若同时要获取资料并保存文件，至少拆成获取 + 写入两个节点。"
                + "\n若同时要读取表格并统计，至少拆成读取 + 计算两个节点。"
                + "\n不要把打开/读取/解析同一文件拆成多个节点。";
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
            String cap = textOr(n, "capability", "");
            List<String> deps = new ArrayList<>();
            JsonNode d = n.get("dependsOn");
            if (d == null) {
                d = n.get("depends_on");
            }
            if (d != null && d.isArray()) {
                for (JsonNode x : d) {
                    deps.add(x.asText());
                }
            }
            int priority = n.has("priority") ? n.get("priority").asInt(50) : 50;
            if (priority < 0) {
                priority = 0;
            }
            if (priority > 100) {
                priority = 100;
            }
            JsonNode dwNode = n.get("doneWhen");
            if (dwNode == null) {
                dwNode = n.get("done_when");
            }
            list.add(new TaskNode(id, name, cap, deps, stringList(n, "inputs"),
                    stringList(n, "outputs"), TaskNodeStatus.PENDING,
                    priority, DoneWhen.parse(dwNode), "", "", 0, ""));
        }
        return DataflowNormalizer.normalize(new TaskGraph(list));
    }

    /** 解析模型完整编译结果，同时保留 Goal 根字段而非丢回 TaskPlan。 */
    ParsedCompilation parseCompilation(String text, Goal base) throws Exception {
        JsonNode root = MAPPER.readTree(extractJson(text));
        TaskGraph graph = parseGraph(text);
        Goal seed = base == null
                ? new Goal("goal_" + UUID.randomUUID().toString().substring(0, 8), "",
                TaskSignals.NONE.describe(), null, Map.of(), List.of(), List.of())
                : base;
        String objective = textOr(root, "objective", seed.objective());
        String signals = textOr(root, "signals", seed.signals());
        String taskType = textOr(root, "taskType", seed.taskType());
        Map<String, String> entities = stringMap(root.get("entities"), seed.entities());
        List<String> constraints = stringList(root, "constraints");
        if (constraints.isEmpty()) constraints = seed.constraints();
        List<String> criteria = stringList(root, "successCriteria");
        if (criteria.isEmpty()) {
            criteria = stringList(root, "success_criteria");
        }
        if (criteria.isEmpty()) {
            criteria = seed.successCriteria();
        }
        Goal goal = new Goal(seed.goalId(), objective, signals, taskType, entities,
                constraints, checkableCriteria(criteria, objective, graph));
        return new ParsedCompilation(goal, graph);
    }

    /**
     * 终验只认文件名，或图上已有的 llm_judge/validation criteria。
     */
    static List<String> checkableCriteria(List<String> raw, String objective,
                                          TaskGraph graph) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        String fromObj = DataflowNormalizer.pathFromName(objective);
        if (!fromObj.isBlank() && graph != null
                && StepEvaluator.hasFileDeliverable(graph, fromObj)) {
            out.add(fromObj);
        }
        if (raw != null) {
            for (String c : raw) {
                if (StringUtils.isBlank(c) || Goal.isPlaceholderCriterion(c)) {
                    continue;
                }
                String path = DataflowNormalizer.pathFromName(c);
                if (!path.isBlank()) {
                    out.add(path);
                    continue;
                }
                if (StepEvaluator.hasMatchingJudge(graph, c)) {
                    out.add(c.trim());
                }
            }
        }
        return List.copyOf(out);
    }

    TaskGraph templateGraph(String userMessage, TaskPlan plan) {
        String blob = DecompositionPolicy.blob(userMessage, plan);
        String path = DecompositionPolicy.firstPath(blob);
        String url = DecompositionPolicy.firstUrl(blob);
        if (DecompositionPolicy.looksLikeDiagram(userMessage, plan)) {
            return DataflowNormalizer.normalize(diagramTemplate(path));
        }
        if (DecompositionPolicy.fetchWrite(userMessage, plan)) {
            return DataflowNormalizer.normalize(fetchWriteTemplate(path, url));
        }
        if (DecompositionPolicy.readThenAnalyze(userMessage, plan)) {
            return DataflowNormalizer.normalize(readAnalyzeTemplate(path));
        }
        if (DecompositionPolicy.researchThenFile(userMessage, plan)) {
            return DataflowNormalizer.normalize(researchThenFileTemplate(path, url));
        }
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
                String cap = inferFromPlan(plan);
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
            if (!path.isBlank() && !name.contains(path)) {
                name = abbreviate(name + " " + path, 40);
            }
            String cap = inferFromPlan(plan);
            if (!url.isBlank() && (StringUtils.isBlank(cap)
                    || CapabilityRegistry.GENERAL.equals(cap))) {
                cap = CAP_WEB;
            }
            TaskNode n1 = new TaskNode("n1", name, cap, List.of(), List.of(), List.of(),
                    TaskNodeStatus.PENDING, 10, DoneWhen.note(), "", "", 0, "");
            n1 = withUrlArg(n1, url);
            nodes.add(n1);
        }
        return schedulableOrClarify(
                DataflowNormalizer.normalize(new TaskGraph(nodes)));
    }

    static TaskGraph diagramTemplate() {
        return diagramTemplate("");
    }

    static TaskGraph diagramTemplate(String path) {
        String mmd = DIAGRAM_MMD;
        String png = DIAGRAM_PNG;
        if (StringUtils.isNotBlank(path)) {
            String p = path.trim();
            int dot = p.lastIndexOf('.');
            String stem = dot > 0 ? p.substring(0, dot) : p;
            String lower = p.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".mmd")) {
                mmd = p;
                png = stem + ".png";
            } else if (lower.endsWith(".png")) {
                png = p;
                mmd = stem + ".mmd";
            }
        }
        return new TaskGraph(List.of(
                new TaskNode("n1",
                        "把架构写成 Mermaid 写入 " + mmd,
                        "image", List.of(), List.of(), List.of("diagram_mmd"),
                        TaskNodeStatus.PENDING, 10,
                        DoneWhen.file(mmd), "", "", 0, ""),
                new TaskNode("n2",
                        "把 " + mmd + " 渲染成 " + png,
                        "image", List.of("n1"), List.of("diagram_mmd"), List.of("diagram_png"),
                        TaskNodeStatus.PENDING, 9,
                        DoneWhen.file(png), "", "", 0, "")));
    }

    static TaskGraph researchThenFileTemplate() {
        return researchThenFileTemplate("", "");
    }

    static TaskGraph researchThenFileTemplate(String path) {
        return researchThenFileTemplate(path, "");
    }

    static TaskGraph researchThenFileTemplate(String path, String url) {
        String file = persistPath(path);
        TaskNode n1 = new TaskNode("n1", "获取资料原文",
                "web", List.of(), List.of(), List.of(ARTIFACT_SOURCE),
                TaskNodeStatus.PENDING, 10,
                DoneWhen.note(), "", "", 0, "");
        n1 = withUrlArg(n1, url);
        return new TaskGraph(List.of(
                n1,
                new TaskNode("n2", "整理并写入 " + file,
                        "file_write", List.of("n1"), List.of(ARTIFACT_SOURCE),
                        List.of(ARTIFACT_NOTES), TaskNodeStatus.PENDING, 9,
                        DoneWhen.file(file), "", "", 0, "")));
    }

    static TaskGraph readAnalyzeTemplate() {
        return readAnalyzeTemplate("");
    }

    static TaskGraph readAnalyzeTemplate(String path) {
        String table = StringUtils.isBlank(path) ? "表格" : path;
        TaskNode n1 = new TaskNode("n1", "读取 " + table,
                "file_read", List.of(), List.of(), List.of(ARTIFACT_TABLE),
                TaskNodeStatus.PENDING, 10,
                DoneWhen.note(), "", "", 0, "");
        if (DecompositionPolicy.isSpreadsheet(path)) {
            n1 = n1.withToolArguments(Map.of(ActionBinder.ARG_PATH, path.trim()));
        }
        return new TaskGraph(List.of(
                n1,
                new TaskNode("n2", "统计每个月销售额",
                        "code", List.of("n1"), List.of(ARTIFACT_TABLE),
                        List.of(ARTIFACT_STATS), TaskNodeStatus.PENDING, 9,
                        DoneWhen.judge("统计覆盖各月销售额"), "", "", 0, "")));
    }

    static TaskGraph fetchWriteTemplate() {
        return fetchWriteTemplate("", "");
    }

    static TaskGraph fetchWriteTemplate(String path) {
        return fetchWriteTemplate(path, "");
    }

    static TaskGraph fetchWriteTemplate(String path, String url) {
        String file = persistPath(path);
        TaskNode n1 = new TaskNode("n1",
                "打开页面抽取全部章节",
                "browser", List.of(), List.of(), List.of(ARTIFACT_SOURCE),
                TaskNodeStatus.PENDING, 10,
                DoneWhen.note(),
                "", "", 0, "");
        n1 = withUrlArg(n1, url);
        return new TaskGraph(List.of(
                n1,
                new TaskNode("n2", "按真实章节写入 " + file,
                        "file_write", List.of("n1"), List.of(ARTIFACT_SOURCE),
                        List.of(ARTIFACT_NOTES), TaskNodeStatus.PENDING, 9,
                        DoneWhen.file(file), "", "", 0, ""),
                new TaskNode("n3", "对照临时文件校验学习文档是否写全",
                        "deliver", List.of("n1", "n2"),
                        List.of(ARTIFACT_SOURCE, ARTIFACT_NOTES), List.of(),
                        TaskNodeStatus.PENDING, 8,
                        DoneWhen.judge("学习文档须覆盖临时文件全部章节且无大段缺失"),
                        "", "", 0, "")));
    }

    private static TaskNode withUrlArg(TaskNode node, String url) {
        if (node == null || StringUtils.isBlank(url)) {
            return node;
        }
        return node.withToolArguments(Map.of(ActionBinder.ARG_URL, url.trim()));
    }

    static TaskGraph clarifyGraph() {
        return new TaskGraph(List.of(
                new TaskNode("n1", CLARIFY_HINT, CAP_PLAN, List.of(), List.of(),
                        List.of("clarification"), TaskNodeStatus.AWAITING_CONFIRM, 10,
                        DoneWhen.note(), "", CLARIFY_HINT, 0, "")));
    }

    static boolean isClarifyGraph(TaskGraph graph) {
        if (graph == null || graph.nodes().size() != 1) {
            return false;
        }
        TaskNode n = graph.nodes().get(0);
        return n.status() == TaskNodeStatus.AWAITING_CONFIRM
                && CAP_PLAN.equalsIgnoreCase(n.capability());
    }

    /**
     * 结构已经能编出可调度图时不再问 LLM。
     * 澄清图除外：缺参时仍让模型先试，失败再澄清。
     */
    static boolean structureDeterminate(TaskGraph graph, String userMessage, TaskPlan plan) {
        if (graph == null || graph.isEmpty() || isClarifyGraph(graph)) {
            return false;
        }
        if (DecompositionPolicy.fetchWrite(userMessage, plan)
                || DecompositionPolicy.readThenAnalyze(userMessage, plan)
                || DecompositionPolicy.researchThenFile(userMessage, plan)
                || DecompositionPolicy.looksLikeDiagram(userMessage, plan)) {
            return true;
        }
        if (graph.nodes().size() != 1) {
            return false;
        }
        TaskNode n = graph.nodes().get(0);
        DoneWhen dw = n.doneWhen();
        if (dw != null && (dw.isFile() || dw.isMedia())) {
            return true;
        }
        String blob = DecompositionPolicy.blob(userMessage, plan);
        return CAP_WEB.equalsIgnoreCase(n.capability())
                && !DecompositionPolicy.firstUrl(blob).isBlank();
    }

    private static String persistPath(String path) {
        return StringUtils.isBlank(path) ? NOTES_FILE : path.trim();
    }

    private static TaskGraph schedulableOrClarify(TaskGraph graph) {
        if (graph == null || graph.isEmpty() || hasUnschedulable(graph)) {
            return clarifyGraph();
        }
        return graph;
    }

    private static boolean hasUnschedulable(TaskGraph graph) {
        for (TaskNode n : graph.nodes()) {
            String cap = n.capability() == null ? "" : n.capability();
            if (!PlanValidator.schedulableCapability(cap)) {
                return true;
            }
            if (DataflowNormalizer.needsFileAcceptance(n) && n.doneWhen().isNote()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 兜底节点的 capability 只看命中的事实信号，不刮步骤名、不读类别。
     * 空/general 再由 {@link DataflowNormalizer#inferCapability} 看 doneWhen。
     *
     * <p>取信号的顺序 = 从「最终交付物」往「中间动作」退：
     * 要先出图就归 image，要对线上做动作就归 web，要落盘就归 file_write，
     * 只是取资料归 research，纯问答归 qa，其余交给 doneWhen 兜底。
     * 一个节点同时命中多个时，交付物那一侧优先 —— 调度器需要的是
     * 「这一步最终要交什么」，不是「它顺便查了什么」。</p>
     */
    static String inferFromPlan(TaskPlan plan) {
        if (plan == null) {
            return CapabilityRegistry.GENERAL;
        }
        TaskSignals s = plan.signals();
        if (s.diagram() || (s.pureImage() && !s.needsFiles())) {
            return "image";
        }
        if (s.publish()) {
            return CAP_WEB;
        }
        if (s.needsFiles()) {
            return "file_write";
        }
        if (s.needsWeb()) {
            return "research";
        }
        if (s.lightTurn()) {
            return "qa";
        }
        return CapabilityRegistry.GENERAL;
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

    private static TaskGraph markPending(TaskGraph g) {
        if (g == null) {
            return g;
        }
        List<TaskNode> list = new ArrayList<>();
        for (TaskNode n : g.nodes()) {
            if (n.status() == TaskNodeStatus.AWAITING_CONFIRM) {
                list.add(n);
            } else {
                list.add(n.withStatus(TaskNodeStatus.PENDING));
            }
        }
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
