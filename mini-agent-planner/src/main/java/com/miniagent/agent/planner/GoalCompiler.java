package com.miniagent.agent.planner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.agent.task.TaskPlan;
import com.miniagent.agent.llm.DedicatedChatModel;
import com.miniagent.agent.skill.SkillStore;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Task Compiler：NL + 任务信号 → Goal + TaskGraph。只产出图，不验收。
 */
@Component
public class GoalCompiler {

    private static final Logger log = LoggerFactory.getLogger(GoalCompiler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    static final String CAP_PLAN = "plan";
    static final String CLARIFY_HINT = "请补充：交付文件名或数据来源（URL）";

    /**
     * 编译器系统提示。词表由 {@link #capabilityVocabulary()} 从
     * {@link com.miniagent.agent.tool.CapabilityRegistry#PLANNER_CAPABILITIES} 注入，
     * 这里不再写第二份 —— 同一份事实写两处，漂移只是时间问题
     * （2026-10-15 实测：系统提示里 image 只被解释成「架构图/流程图」，
     * 模型遇到「写一首歌」就只能标 file_write，生歌工具被提案闸门裁在门外）。
     */
    private static final String COMPILER_SYSTEM_TEMPLATE = """
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
            "intent": {
              "objective": "用户最终目标",
              "deliverables": [{"name":"交付物", "type":"file|media|answer|action", "path":"", "required":true}],
              "inputs": [{"name":"输入材料", "source":"用户消息/附件/网页/文件", "required":true}],
              "constraints": ["必须遵守的限制"],
              "exclusions": ["明确禁止或不需要的操作"],
              "acceptanceCriteria": ["可验证的完成条件"],
              "needsClarification": false,
              "clarificationQuestion": ""
            },
            "entities": {"k": "v"},
            "nodes": [
                {"id": "n1", "name": "...", "capability": "...",
                 "dependsOn": [], "inputs": [], "outputs": [], "priority": 50,
                 "doneWhen": {"type": "note_required", "path": "", "criteria": ""}}
              ]
            }

            successCriteria 只填可验收项：workspace 相对文件名，或与某节点
            doneWhen.criteria 完全相同的评判句。禁止「完成 objective 且可验收」。

            capability 取值：{CAPS}。禁止 general：调度器无法从它判断
            工具面和验收，归不了类就拆成有明确能力的节点。
            读表格/已有文件用 file_read；写文档、报告落盘用 file_write；
            写代码/改代码用 code；跑命令、编译、测试用 shell；
            最终交付物用 deliver。
            image 是**媒体生成**能力，覆盖图片、音频、视频、架构图/流程图：
            生成图片/插画、创作歌曲或配乐、把已有图片改风格、以及架构图，
            一律用 image。不要因为「要出一首歌」就退化成 file_write 去写
            一个歌词文件 —— 那只交付了文本，不是用户要的音频；
            只有用户明确说「只要歌词文本、不要音频」时才用 file_write。
            架构图/流程图用 image 时：先产出 .mmd 再渲染 .png，不要只交 Mermaid 源码；
            生成媒体产物（图片/音频/视频）的节点，doneWhen.type 用 media_delivered。
            涉及网页的任务按用户真实动作拆：要整篇正文（飞书/wiki 等长文）就用
            browser 一次抽全再落盘；要点击、填表、读取局部字段，就拆成打开页面 →
            看结构 → 交互，不要一律套「抽正文写文档」。
            doneWhen.type 仅：note_required / file_exists / media_delivered / llm_judge /
            command_success / validation_passed。
            file_exists 填 path，可加 criteria 做内容验收。
            llm_judge、validation_passed 可填 criteria。
            command_success 要求 evidence 含 exit_code=0。

            【不要放大用户请求】
            交付物必须能在用户原文里找到依据。用户没有明确要求保存、导出、写入文件，
            也没有给出输出路径时：禁止生成 file_write 节点，禁止把「整理成某文件」
            写进 objective / deliverables / successCriteria。这类请求的交付物是
            对话内的回答（deliverable type=answer），不是落盘文件。
            例：用户说「搜索今日 AI 技术最新动态」→ 一个 web 节点 + note_required，
            不要在目标里追加「并整理成 Markdown 简报文件」。

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

            【复用已有 Procedure】
            用户消息里若列出了可复用的 Procedure（带 procedureId 与 version），且某个节点
            确实按它的步骤执行，必须在该节点上显式声明：
              "procedureId": <列表中的 id>, "procedureVersion": <列表中的 version>
            这两个字段是可选的，但一旦声明就必须成对出现，且只能取本轮列表里的值——
            不要编造 id，不要自己估版本号。不按任何 Procedure 执行时两个字段都省略。
            声明会被记成"这一版 Procedure 在该节点被使用了一次"，用于统计它的成功率；
            所以宁可省略，也不要把不相关的 Procedure 挂到节点上。
            """;

    /**
     * 给模型看的 capability 词表。
     *
     * <p>取自 {@link CapabilityRegistry#PLANNER_CAPABILITIES}（唯一事实源）并去掉
     * {@code general} —— 那是对模型禁用的，做兜底出口用，不能出现在词表里。</p>
     */
    static String capabilityVocabulary() {
        return CapabilityRegistry.PLANNER_CAPABILITIES.stream()
                .filter(c -> !CapabilityRegistry.GENERAL.equalsIgnoreCase(c))
                .collect(Collectors.joining(" / "));
    }

    /** 词表在装配期注入：PACKS 加了能力而提示词没跟上，正是这次生歌用不上的直接原因。 */
    static String compilerSystem() {
        return COMPILER_SYSTEM_TEMPLATE.replace("{CAPS}", capabilityVocabulary());
    }

    private final PlannerProperties properties;
    private volatile ChatModel dedicatedPlanner;

    /**
     * skill 摘要来源，喂给规划模型看。
     *
     * <p><b>为什么规划器也要看 skill</b>：它只拿到用户消息时，不知道系统里存在
     * 「生歌技能」，于是给写歌任务挑了个只能写文件的 capability，提案闸门再把
     * {@code song_generate} 裁掉 —— 模型从头到尾没见过这个工具。执行阶段的模型
     * 也能看到 skill 摘要，但那时节点 capability 已经定死，摘要救不了它。</p>
     *
     * <p>用 {@code required = false} 的显式注解而不是裸字段：planner 单测不装配
     * skill 时应退化成「不看技能」。裸字段漏注解的后果是字段永远为 null 而
     * 工具照样注册成功，只在调用时才炸（{@code BuiltinTools.skillStore} 就是这样
     * 变成三个空壳工具的）。</p>
     */
    @Autowired(required = false)
    private SkillStore skillStore;

    @Autowired(required = false)
    private ProcedurePromptPort procedurePromptPort;

    /** skill 摘要给规划模型的字符上限：词表之外还要留预算给任务描述。 */
    private static final int SKILL_DIGEST_MAX = 1200;

    private String procedureDigest(String prefix) {
        if (procedurePromptPort == null) {
            return prefix;
        }
        List<ProcedurePromptInput> procedures = procedurePromptPort.findForCurrentOwner("");
        if (procedures == null || procedures.isEmpty()) {
            return prefix;
        }
        String digest = procedures.stream().limit(20)
                .map(p -> "procedureId=" + p.procedureId() + " version=" + p.version()
                        + " name=" + p.name() + " description=" + p.description()
                        + " preconditions=" + p.preconditions() + " steps=" + p.steps()
                        + " successConditions=" + p.successConditions())
                .collect(Collectors.joining("\\n"));
        return prefix + "\\n可复用的用户 Procedure（只使用下列 ID/version；引用需由本轮绑定）：\\n" + digest;
    }

    /** 装配/测试用：显式指定 skill 来源。生产走 {@code @Autowired(required=false)}。 */
    void attachSkillStore(SkillStore store) {
        this.skillStore = store;
    }

    /**
     * 把可用技能拼进规划模型的输入。摘要为空（没装 skill / 单测未装配）就返回空串，
     * 不改变原有行为。
     */
    String skillDigest() {
        if (skillStore == null) {
            return procedureDigest("");
        }
        String summary;
        try {
            summary = skillStore.getSkillListSummary();
        } catch (Exception e) {
            log.warn("读取 skill 摘要失败，按无技能继续拆解: {}", e.getMessage());
            return procedureDigest("");
        }
        if (StringUtils.isBlank(summary)) {
            return procedureDigest("");
        }
        if (summary.length() > SKILL_DIGEST_MAX) {
            summary = summary.substring(0, SKILL_DIGEST_MAX) + "\n（技能清单已截断）";
        }
        return procedureDigest("\n本轮可用技能：若用户目标命中某技能，该步骤的 capability 必须选到"
                + "它所依赖的能力类别，不要退化成只能写文件：\n" + summary);
    }

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

    public enum CompilationSource {
        MODEL,
        /** 续跑：图来自已持久化快照，本轮没有重新调用规划模型。 */
        RESUMED,
        MODEL_UNAVAILABLE,
        MODEL_EMPTY,
        MODEL_ERROR,
        VALIDATION_ERROR
    }

    public record CompileResult(Goal goal, TaskGraph graph, boolean degradedCompilation,
                                CompilationSource source, String fallbackReason,
                                TaskIntent intent) {
        public CompileResult {
            source = source == null
                    ? (degradedCompilation ? CompilationSource.MODEL_ERROR : CompilationSource.MODEL)
                    : source;
            fallbackReason = fallbackReason == null ? "" : fallbackReason;
            intent = intent == null ? TaskIntent.fromGoal(goal) : intent;
        }

        public CompileResult(Goal goal, TaskGraph graph, boolean degradedCompilation,
                             CompilationSource source, String fallbackReason) {
            this(goal, graph, degradedCompilation, source, fallbackReason, TaskIntent.fromGoal(goal));
        }

        public CompileResult(Goal goal, TaskGraph graph, boolean degradedCompilation) {
            this(goal, graph, degradedCompilation,
                    degradedCompilation ? CompilationSource.MODEL_ERROR : CompilationSource.MODEL,
                    "", TaskIntent.fromGoal(goal));
        }

        public boolean degraded() {
            return degradedCompilation || source == CompilationSource.MODEL_UNAVAILABLE
                    || source == CompilationSource.MODEL_EMPTY
                    || source == CompilationSource.MODEL_ERROR
                    || source == CompilationSource.VALIDATION_ERROR;
        }
    }
    public record ParsedCompilation(Goal goal, TaskGraph graph, TaskIntent intent) {
        public ParsedCompilation(Goal goal, TaskGraph graph) {
            this(goal, graph, TaskIntent.fromGoal(goal));
        }
    }
    private record LlmCompileAttempt(CompileResult result, CompilationSource source,
                                     String failureReason) {}

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
                parsed = alignWithRequest(parsed, plan);
                if (parsed != null && parsed.graph() != null && !parsed.graph().isEmpty()) {
                    return new CompileResult(parsed.goal(), markPending(parsed.graph()), false,
                            CompilationSource.MODEL, "", parsed.intent());
                }
                log.warn("GoalCompiler 带修正的 LLM 编译得到空图");
            } catch (Exception e) {
                log.warn("GoalCompiler 带修正的 LLM 编译失败: {}", e.getMessage());
            }
        }

        // 修正编译失败时返回空图，不构造规则猜测的任务图。
        // 规则猜测的图不是模型对本次请求的理解，
        // 用它继续执行等于把「结构合法」当成「语义正确」，正是分项执行出错的来源。
        return failedCompile(base, CompilationSource.MODEL_ERROR,
                "MODEL_ERROR: correction compile returned no valid graph");
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
                + "\n任务目标:" + (plan == null ? "" : plan.taskGoal())
                + skillDigest()
                + "\n\n**修正要求**:\n" + correctionPrompt
                + "\n\n请根据上述修正要求重新生成任务图。";

        var response = chat.chat(ChatRequest.builder()
                .messages(List.of(new SystemMessage(compilerSystem()), UserMessage.from(user)))
                .build());
        String text = response.aiMessage() == null ? "" : response.aiMessage().text();
        try {
            ParsedCompilation parsed = parseCompilation(text, goalFromPlan(userMessage, plan));
            if (parsed == null || parsed.graph() == null || parsed.graph().isEmpty()) {
                // 空图是最难定位的一类失败：模型回的是**合法 JSON 但没有可用的 nodes 数组**，
                // 所以既不抛异常、也没有任何痕迹 —— 外部只能看到一次空结果。
                // 这段原文此前完全不落盘（agent_trace_steps 里 CONTEXT_LOAD → GOAL_COMPILED
                // 之间本来就是空白），于是「为什么返回空图」只能靠猜。
                log.warn("GoalCompiler 模型返回空图，原文前 400 字: {}", abbreviate(text, 400));
            }
            return parsed;
        } catch (Exception e) {
            throw new IllegalStateException("parse graph failed: " + e.getMessage()
                    + " | 原文前 300 字: " + abbreviate(text, 300), e);
        }
    }

    /**
     * 编译本轮任务图。只有用户显式进入 Plan mode 时调用；拆解只由规划模型完成。
     */
    public CompileResult compile(ChatModel chat, String userMessage, TaskPlan plan) {
        Goal base = goalFromPlan(userMessage, plan);

        // 一律交给 LLM 拆解。规则穷举猜结构不参与运行路径：
        // 它把「结构合法」当成「语义正确」，是分项任务出错的主要来源。
        LlmCompileAttempt attempt = tryLlmCompile(chat, userMessage, plan);
        if (attempt.result() != null) {
            return attempt.result();
        }
        return failedCompile(base, attempt.source(), attempt.failureReason());
    }

    /** 叫规划模型编一张图，并返回失败原因。 */
    private LlmCompileAttempt tryLlmCompile(ChatModel chat, String userMessage, TaskPlan plan) {
        ChatModel planner = resolvePlannerChat(chat);
        // 无可用规划模型时直接失败。进入规划器意味着用户显式选择了 Plan mode，
        // 任务拆解只能由 LLM 完成，没有模板兜底。
        if (planner == null) {
            log.warn("GoalCompiler 跳过 LLM 拆解：无可用规划模型（传入 chat={}，专用模型={}）",
                    chat == null ? "null" : chat.getClass().getSimpleName(),
                    dedicatedPlanner == null ? "未配置" : "已配置");
            return new LlmCompileAttempt(null, CompilationSource.MODEL_UNAVAILABLE,
                    "NO_PLANNER_MODEL");
        }
        int retries = Math.max(0, properties.getCompilerRetry());
        boolean modelError = false;
        String lastError = "";
        for (int i = 0; i <= retries; i++) {
            long t0 = System.currentTimeMillis();
            try {
                ParsedCompilation parsed = compileWithLlm(planner, userMessage, plan);
                parsed = alignWithRequest(parsed, plan);
                long ms = System.currentTimeMillis() - t0;
                if (parsed != null && parsed.graph() != null && !parsed.graph().isEmpty()) {
                    log.info("GoalCompiler LLM 拆解成功 retry={} nodes={} 耗时={}ms 模型={}",
                            i, parsed.graph().nodes().size(), ms,
                            planner.getClass().getSimpleName());
                    return new LlmCompileAttempt(new CompileResult(
                            parsed.goal(), markPending(parsed.graph()), false,
                            CompilationSource.MODEL, "", parsed.intent()), CompilationSource.MODEL, "");
                }
                log.warn("GoalCompiler LLM 拆解得到空图 retry={} 耗时={}ms（原文见上一条 warn）",
                        i, ms);
            } catch (Exception e) {
                modelError = true;
                lastError = abbreviate(e.getMessage(), 180);
                log.warn("GoalCompiler LLM 拆解失败 retry={} 耗时={}ms: {}",
                        i, System.currentTimeMillis() - t0, e.getMessage());
            }
        }
        return new LlmCompileAttempt(null,
                modelError ? CompilationSource.MODEL_ERROR
                        : CompilationSource.MODEL_EMPTY,
                modelError ? "MODEL_ERROR:" + lastError : "MODEL_EMPTY_OR_INVALID_GRAPH");
    }

    /** 规划模型不可用 / 空图 / 解析失败时返回空图结果，由调用方处理规划失败。 */
    private CompileResult failedCompile(Goal base, CompilationSource source, String reason) {
        return new CompileResult(base, new TaskGraph(List.of()), true, source, reason);
    }

    /**
     * 把模型产出的目标与用户原文的授权对齐。
     *
     * <p>用户没有要求文件交付时，{@link PlanValidator} 会拒绝整张图 —— 这条硬约束是对的，
     * 但它只拒绝了图：模型仍把「产出某个文件」写进了 objective 与 deliverables。
     * 两者一旦不一致，重规划会来回摇摆（实测 2 → 1 → 4 个错误），
     * 失败审批还会把这个被放大的目标带进审批卡，用户看到的就是
     * 「目标是写文件、计划只有一步搜索」。</p>
     *
     * <p>因此在进校验之前先对齐：去掉未授权的落盘节点、文件交付物与文件验收项，
     * 目标文本回退到用户原文。这是一次有日志的纠正，不是静默改写。</p>
     */
    ParsedCompilation alignWithRequest(ParsedCompilation parsed, TaskPlan plan) {
        if (parsed == null || parsed.graph() == null || plan == null
                || PlanValidator.fileDeliveryRequested(plan)) {
            return parsed;
        }
        boolean graphDirty = parsed.graph().nodes().stream()
                .anyMatch(PlanValidator::isFileDeliveryNode);
        boolean intentDirty = parsed.intent().deliverables().stream()
                .anyMatch(GoalCompiler::isPersistDeliverable);
        boolean objectiveDirty = PlanValidator.claimsFileDelivery(parsed.goal().objective())
                || PlanValidator.claimsFileDelivery(parsed.intent().objective());
        if (!graphDirty && !intentDirty && !objectiveDirty) {
            return parsed;
        }
        TaskGraph graph = graphDirty ? dropPersistNodes(parsed.graph()) : parsed.graph();
        String userGoal = StringUtils.trimToEmpty(plan.taskGoal());
        String objective = objectiveDirty && StringUtils.isNotBlank(userGoal)
                ? userGoal : parsed.goal().objective();
        Goal goal = new Goal(parsed.goal().goalId(), objective, parsed.goal().signals(),
                parsed.goal().taskType(), parsed.goal().entities(), parsed.goal().constraints(),
                checkableCriteria(dropUnauthorizedFileCriteria(
                        parsed.goal().successCriteria(), graph), objective, graph));
        TaskIntent source = parsed.intent();
        TaskIntent intent = new TaskIntent(objective,
                source.deliverables().stream()
                        .filter(d -> !isPersistDeliverable(d)).toList(),
                source.inputs(), source.constraints(), source.exclusions(),
                dropUnauthorizedFileCriteria(source.acceptanceCriteria(), graph),
                source.needsClarification(), source.clarificationQuestion());
        log.warn("GoalCompiler 纠正未授权的文件交付 droppedNodes={} objectiveAligned={}",
                parsed.graph().nodes().size() - graph.nodes().size(), objectiveDirty);
        return new ParsedCompilation(goal, graph, intent);
    }

    /** 去掉落盘节点，并修补依赖与输入引用，避免留下指向已删节点的悬空边。 */
    private static TaskGraph dropPersistNodes(TaskGraph graph) {
        Set<String> droppedIds = new LinkedHashSet<>();
        Set<String> droppedOutputs = new LinkedHashSet<>();
        for (TaskNode n : graph.nodes()) {
            if (PlanValidator.isFileDeliveryNode(n)) {
                droppedIds.add(n.id());
                droppedOutputs.addAll(n.outputs());
            }
        }
        if (droppedIds.isEmpty()) {
            return graph;
        }
        List<TaskNode> kept = new ArrayList<>();
        for (TaskNode n : graph.nodes()) {
            if (droppedIds.contains(n.id())) {
                continue;
            }
            List<String> deps = new ArrayList<>();
            Set<String> liveOutputs = new LinkedHashSet<>();
            for (String d : n.dependsOn()) {
                if (droppedIds.contains(d)) {
                    continue;
                }
                deps.add(d);
                TaskNode dep = graph.byId(d);
                if (dep != null) {
                    liveOutputs.addAll(dep.outputs());
                }
            }
            List<String> inputs = n.inputs().stream()
                    .filter(in -> !droppedOutputs.contains(in) || liveOutputs.contains(in))
                    .toList();
            kept.add(new TaskNode(n.id(), n.name(), n.capability(), deps, inputs, n.outputs(),
                    n.status(), n.priority(), n.doneWhen(), n.toolHint(), n.toolArguments(),
                    n.compensation(), n.covers(), n.lastError(), n.retryCount(), n.output(),
                    n.outputBindings()));
        }
        return new TaskGraph(kept);
    }

    private static boolean isPersistDeliverable(TaskIntent.Deliverable d) {
        String type = d.type() == null ? "" : d.type().trim().toLowerCase(java.util.Locale.ROOT);
        return StringUtils.isNotBlank(d.path()) || "file".equals(type) || "media".equals(type);
    }

    private static boolean isUnauthorizedFileCriterion(String criterion, TaskGraph graph) {
        String path = DataflowNormalizer.pathFromName(criterion);
        return StringUtils.isNotBlank(path) && !StepEvaluator.hasFileDeliverable(graph, path);
    }

    private static List<String> dropUnauthorizedFileCriteria(List<String> criteria, TaskGraph graph) {
        return criteria == null ? List.of() : criteria.stream()
                .filter(c -> !isUnauthorizedFileCriterion(c, graph))
                .toList();
    }

    private Goal goalFromPlan(String userMessage, TaskPlan plan) {
        String objective = plan != null && StringUtils.isNotBlank(plan.taskGoal())
                ? plan.taskGoal() : (userMessage == null ? "" : userMessage);
        return new Goal(
                "goal_" + UUID.randomUUID().toString().substring(0, 8),
                objective, "", Map.of(), List.of(),
                checkableCriteria(List.of(), objective, null));
    }

    private ParsedCompilation compileWithLlm(ChatModel chat, String userMessage, TaskPlan plan) {
        if (chat == null) {
            return null;
        }
        String user = "用户消息:\n" + userMessage
                + "\n任务目标:" + (plan == null ? "" : plan.taskGoal())
                + "\n若同时要获取资料并保存文件，至少拆成获取 + 写入两个节点。"
                + "\n若同时要读取表格并统计，至少拆成读取 + 计算两个节点。"
                + "\n不要把打开/读取/解析同一文件拆成多个节点。"
                + skillDigest();
        var response = chat.chat(ChatRequest.builder()
                .messages(List.of(new SystemMessage(compilerSystem()), UserMessage.from(user)))
                .build());
        String text = response.aiMessage() == null ? "" : response.aiMessage().text();
        try {
            ParsedCompilation parsed = parseCompilation(text, goalFromPlan(userMessage, plan));
            if (parsed == null || parsed.graph() == null || parsed.graph().isEmpty()) {
                // 空图是最难定位的一类失败：模型回的是**合法 JSON 但没有可用的 nodes 数组**，
                // 所以既不抛异常、也没有任何痕迹 —— 外部只能看到一次空结果。
                // 这段原文此前完全不落盘（agent_trace_steps 里 CONTEXT_LOAD → GOAL_COMPILED
                // 之间本来就是空白），于是「为什么返回空图」只能靠猜。
                log.warn("GoalCompiler 模型返回空图，原文前 400 字: {}", abbreviate(text, 400));
            }
            return parsed;
        } catch (Exception e) {
            throw new IllegalStateException("parse graph failed: " + e.getMessage()
                    + " | 原文前 300 字: " + abbreviate(text, 300), e);
        }
    }

    TaskGraph parseGraph(String text) throws Exception {
        return parseGraph(text, List.of());
    }

    TaskGraph parseGraph(String text, List<ProcedurePromptInput> allowedProcedures) throws Exception {
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
            TaskNode node = new TaskNode(id, name, cap, deps, stringList(n, "inputs"),
                    stringList(n, "outputs"), TaskNodeStatus.PENDING,
                    priority, DoneWhen.parse(dwNode), "", "", 0, "");
            if (n.hasNonNull("procedureId") && n.hasNonNull("procedureVersion")
                    && allowedProcedures.stream().anyMatch(p ->
                    p.procedureId().equals(n.get("procedureId").asLong())
                            && p.version().equals(n.get("procedureVersion").asInt()))) {
                node = node.withProcedure(n.get("procedureId").asLong(), n.get("procedureVersion").asInt());
            }
            list.add(node);
        }
        return DataflowNormalizer.normalize(new TaskGraph(list));
    }

    /** 解析模型完整编译结果，同时保留 Goal 根字段而非丢回 TaskPlan。 */
    ParsedCompilation parseCompilation(String text, Goal base) throws Exception {
        JsonNode root = MAPPER.readTree(extractJson(text));
        List<ProcedurePromptInput> available = procedurePromptPort == null
                ? List.of() : procedurePromptPort.findForCurrentOwner("");
        TaskGraph graph = parseGraph(text, available == null ? List.of() : available);
        Goal seed = base == null
                ? new Goal("goal_" + UUID.randomUUID().toString().substring(0, 8), "",
                "", null, Map.of(), List.of(), List.of())
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
        TaskIntent intent = parseIntent(root.get("intent"), goal, graph);
        return new ParsedCompilation(goal, graph, intent);
    }

    private TaskIntent parseIntent(JsonNode node, Goal goal, TaskGraph graph) {
        if (node == null || !node.isObject()) {
            return intentFromGoalAndGraph(goal, graph);
        }
        List<TaskIntent.Deliverable> deliverables = new ArrayList<>();
        JsonNode ds = node.get("deliverables");
        if (ds != null && ds.isArray()) {
            for (JsonNode d : ds) {
                if (d != null && d.isObject()) {
                    deliverables.add(new TaskIntent.Deliverable(
                            textOr(d, "name", ""), textOr(d, "type", ""),
                            textOr(d, "path", ""), !d.has("required") || d.get("required").asBoolean(true)));
                }
            }
        }
        List<TaskIntent.Input> inputs = new ArrayList<>();
        JsonNode ins = node.get("inputs");
        if (ins != null && ins.isArray()) {
            for (JsonNode in : ins) {
                if (in != null && in.isObject()) {
                    inputs.add(new TaskIntent.Input(
                            textOr(in, "name", ""), textOr(in, "source", ""),
                            !in.has("required") || in.get("required").asBoolean(true)));
                }
            }
        }
        List<String> constraints = stringList(node, "constraints");
        if (constraints.isEmpty()) constraints = goal.constraints();
        List<String> criteria = stringList(node, "acceptanceCriteria");
        if (criteria.isEmpty()) criteria = goal.successCriteria();
        return new TaskIntent(textOr(node, "objective", goal.objective()), deliverables, inputs,
                constraints, stringList(node, "exclusions"), criteria,
                node.path("needsClarification").asBoolean(false),
                textOr(node, "clarificationQuestion", ""));
    }

    private static TaskIntent intentFromGoalAndGraph(Goal goal, TaskGraph graph) {
        List<TaskIntent.Deliverable> deliverables = new ArrayList<>();
        List<TaskIntent.Input> inputs = new ArrayList<>();
        if (graph != null) {
            for (TaskNode node : graph.nodes()) {
                if (node.doneWhen() != null && (node.doneWhen().isFile() || node.doneWhen().isMedia())) {
                    String type = node.doneWhen().isFile() ? "file" : "media";
                    deliverables.add(new TaskIntent.Deliverable(
                            node.name(), type, node.doneWhen().path(), true));
                }
                if (node.capability() != null && (node.capability().contains("read")
                        || node.capability().equals("web") || node.capability().equals("research"))) {
                    inputs.add(new TaskIntent.Input(node.name(), node.capability(), true));
                }
            }
        }
        return new TaskIntent(goal == null ? "" : goal.objective(), deliverables, inputs,
                goal == null ? List.of() : goal.constraints(), List.of(),
                goal == null ? List.of() : goal.successCriteria(),
                goal != null && goal.isClarify(),
                goal != null && goal.isClarify() ? "请补充完成任务所需的信息。" : "");
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
