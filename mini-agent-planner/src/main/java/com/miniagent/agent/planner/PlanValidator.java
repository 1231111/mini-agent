package com.miniagent.agent.planner;

import com.miniagent.agent.task.TaskPlan;
import com.miniagent.agent.tool.CapabilityRegistry;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * TaskGraph 结构验收：无环、id 唯一、依赖存在、inputs 由前置 outputs 产出。
 * 支持返回结构化验证报告，用于 replan 闭环修正
 */
@Component
public class PlanValidator {

    /**
     * Planner-facing summary of the structural contract enforced below. Keep this
     * next to the validator so the compiler prompt and implementation are reviewed
     * together whenever a hard rule changes.
     */
    static String compilerHardConstraintsPrompt() {
        return """
                【PlanValidator 调度硬约束】
                - 每个节点的 id、name 都必须非空；id 在整张图内唯一。name 写用户可理解的结果，不能只写内部操作。
                - capability 必须是上方词表里的已知能力，不能为空，也不能是 general。
                - 每个节点都必须有有效 doneWhen；只允许 note_required、file_exists、media_delivered、llm_judge、command_success、validation_passed。command_success.criteria 必须写完整预期测试/命令；file_exists 必须填写 workspace 相对 path。
                - dependsOn 中的每个 id 必须真实存在，依赖图必须无环；节点 inputs 只能引用 intent.inputs 中声明的外部输入，或其 dependsOn 前置节点及其祖先的 outputs。外部输入必须在 intent.inputs 声明名称和来源。
                - 若一项请求同时需要获取和落盘，获取与持久化必须拆为不同节点，并通过 dependsOn 建立数据流依赖；不能把 file_exists 挂在获取能力上。
                - 代码改动必须有独立 shell 测试节点，该节点 command_success 且依赖代码改动节点；测试命令必须实际运行测试，不能跳过或掩盖失败。
                - 不得通过删除用户要求、缩减 intent.constraints/acceptanceCriteria、空 outputs 或降低验收标准来消除校验错误。被拒绝后逐条修复校验报告，同时保留原始用户意图。
                """;
    }

    private final PlannerProperties properties;

    public PlanValidator() {
        this(new PlannerProperties());
    }

    @Autowired
    public PlanValidator(PlannerProperties properties) {
        this.properties = properties == null ? new PlannerProperties() : properties;
    }

    /** 用户明确提出文件交付时才允许规划器落盘。 */
    private static final Pattern FILE_OUTPUT_ACTION = Pattern.compile(
            "(?i)(保存|导出|写入|写出|写到|写成|输出到|输出为|落盘|落地|另存|"
                    + "生成|创建|新建|产出|整理成|做一份|写一份|做一个|写一个|"
                    + "save|export|write|create|generate|produce)");
    private static final Pattern FILE_OUTPUT_TARGET = Pattern.compile(
            "(?i)(文件|文档|报告|资料|markdown|md|sql|代码|脚本|"
                    + "[\\w一-龥 .-]+\\.(txt|md|markdown|java|py|json|xml|yaml|yml|toml|"
                    + "properties|conf|log|html|css|js|tsx?|go|rs|sh|bat|ps1|sql|ipynb|mmd|"
                    + "svg|xlsx|xls|csv|docx|doc|pptx|ppt|pdf|png|jpe?g|zip)\\b)");
    private static final Pattern READ_ONLY_ACTION = Pattern.compile(
            "(?i)(读取|读一下|读读|解析|分析|统计|汇总|提取|抽取|审阅|检查|梳理|总结|"
                    + "看一下|看看|read|parse|analyze|analyse|inspect|review|summarize|extract)");
    private static final Pattern UNAMBIGUOUS_SAVE_ACTION = Pattern.compile(
            "(?i)(保存|导出|写入|写出|写到|写成|输出到|输出为|落盘|落地|另存|"
                    + "save|export)");
    private static final Pattern OPERATION_ONLY_NODE_NAME = Pattern.compile(
            "(?i)^(?:保存(?:文件)?|读取(?:文件)?|读入(?:文件)?|打开(?:文件)?|查看(?:文件)?|"
                    + "运行(?:python(?:脚本)?)?|执行(?:脚本|命令)?|编辑(?:文件)?|修改(?:文件)?|生成|创建|新建)"
                    + "\\s*[「『\\\"']?\\s*(?:[\\w.-]+\\s+)?[\\w./\\\\-]+"
                    + "\\.(?:py|json|java|js|jsx|ts|tsx|go|rs|sh|ps1|html|css|xml|yaml|yml|md|txt)"
                    + "[」』\\\"']?\\s*$");
    private static final Pattern EXECUTION_ONLY_NODE_NAME = Pattern.compile(
            "(?i)^(?:运行|执行|调用|启动|编译|测试)\\s*[\\p{IsHan}A-Za-z0-9_.-]+$");
    private static final Pattern TOOL_ACTION_ONLY_NODE_NAME = Pattern.compile(
            "(?i)^(?:保存|读取|读入|打开|查看|运行|执行|调用|启动|编译|测试|编辑|修改)"
                    + "\\s+(?:python(?:脚本)?|java(?:程序)?|脚本|程序|文件|命令)"
                    + "(?:\\s+[\\p{IsHan}A-Za-z0-9_.-]+)?$");
    private static final Pattern SOURCE_ARTIFACT = Pattern.compile(
            "(?i)(\\.(?:java|py|js|jsx|ts|tsx|go|rs|cs|cpp|c|h|sql|sh|ps1)\\b|"
                    + "源代码|源文件|代码|程序|脚本|模块|函数|类)");

    /**
     * 用户目标里的错误诊断语义。
     *
     * <p>这段判据原来放在任务信号层（{@code TaskSignalMatcher}），由词表匹配后
     * 以一个布尔值传进来。信号层删除后，这里是唯一消费者，判据直接读目标原文：
     * 「用户要的是不是定位一个失败」是图验收的一部分，不需要另一层中间表示。</p>
     */
    private static final Pattern DIAGNOSTIC_GOAL = Pattern.compile(
            "(?i)(错误码|错误代码|报错|报错信息|异常|故障|失败原因|失败信息|错误原因|"
                    + "诊断|故障定位|排查错误|定位问题|为何失败|为什么失败|error\\s*code|"
                    + "error|exception|failure|diagnos|debug|troubleshoot)");

    /**
     * 向后兼容的接受方法，返回 boolean
     * @deprecated 请使用 {@link #validate(TaskGraph, TaskPlan)} 获取结构化报告
     */
    @Deprecated
    public boolean accept(TaskGraph g, TaskPlan plan) {
        return validate(g, plan, null).valid();
    }

    public boolean accept(TaskGraph g, TaskPlan plan, Goal goal) {
        return validate(g, plan, goal).valid();
    }

    public boolean accept(TaskGraph g, TaskPlan plan, Goal goal, TaskIntent intent) {
        return validate(g, plan, goal, intent).valid();
    }

    public PlanValidationReport validate(TaskGraph g, TaskPlan plan) {
        return validate(g, plan, null);
    }

    /**
     * 结构化验证。goal 非空时核对 successCriteria 里的文件是否在图上。
     */
    public PlanValidationReport validate(TaskGraph g, TaskPlan plan, Goal goal) {
        return validate(g, plan, goal, null);
    }

    /** Intent inputs come from outside the graph; node outputs must still have an ancestor producer. */
    public PlanValidationReport validate(TaskGraph g, TaskPlan plan, Goal goal, TaskIntent intent) {
        List<PlanValidationReport.ValidationError> errors = new ArrayList<>();
        List<PlanValidationReport.ValidationWarning> warnings = new ArrayList<>();

        // 1. 检查图是否为空
        if (g == null || g.isEmpty()) {
            errors.add(new PlanValidationReport.ValidationError(
                PlanValidationReport.ValidationError.Type.EMPTY_GRAPH,
                null,
                "任务图为空或未定义",
                "请确保 LLM 生成了有效的任务节点"
            ));
            return new PlanValidationReport(errors, warnings);
        }

        if (g.nodes().size() > properties.getMaxGraphNodes()) {
            errors.add(new PlanValidationReport.ValidationError(
                    PlanValidationReport.ValidationError.Type.GRAPH_TOO_LARGE, null,
                    "Task graph has " + g.nodes().size() + " nodes; configured maximum is "
                            + properties.getMaxGraphNodes() + ".",
                    "Reduce the plan to a smaller set of independently verifiable steps."));
            return new PlanValidationReport(errors, warnings);
        }
        if (g.exceedsDependencyCount(properties.getMaxGraphEdges())) {
            errors.add(new PlanValidationReport.ValidationError(
                    PlanValidationReport.ValidationError.Type.GRAPH_TOO_MANY_EDGES, null,
                    "Task graph exceeds the configured dependency edge limit of "
                            + properties.getMaxGraphEdges() + ".",
                    "Remove redundant dependency links and retain only required ordering."));
            return new PlanValidationReport(errors, warnings);
        }

        boolean hasCycle = g.hasCycle();
        if (hasCycle) {
            errors.add(new PlanValidationReport.ValidationError(
                    PlanValidationReport.ValidationError.Type.HAS_CYCLE, null,
                    "Task graph contains a dependency cycle.",
                    "Remove cyclic dependencies before scheduling."));
        } else if (g.exceedsDependencyDepth(properties.getMaxGraphDepth())) {
            errors.add(new PlanValidationReport.ValidationError(
                    PlanValidationReport.ValidationError.Type.GRAPH_TOO_DEEP, null,
                    "Task graph exceeds the configured dependency depth of "
                            + properties.getMaxGraphDepth() + ".",
                    "Shorten the dependency chain while preserving required ordering."));
        }

        // A canonical waiting receipt defers business acceptance; it does not
        // certify the requested outputs or permit an executable graph.
        if (intent != null && intent.needsClarification()) {
            if (goal == null || !goal.isClarify() || !GoalCompiler.isClarificationWait(g, intent)) {
                errors.add(new PlanValidationReport.ValidationError(
                        PlanValidationReport.ValidationError.Type.GENERAL, null,
                        "待澄清任务缺少与具体问题一致的等待节点",
                        "仅保留问题对应的 AWAITING_CONFIRM 计划节点；澄清完成后重新编译并验收执行图"));
            }
            return new PlanValidationReport(errors, warnings);
        }

        // 3. 检查节点属性。调用本校验器即表示该图将交给调度器，必须严格检查。
        boolean structured = true;
        boolean fileDeliveryAllowed = fileDeliveryRequested(plan);
        Set<String> ids = new HashSet<>();
        for (TaskNode n : g.nodes()) {
            // 检查节点ID和名称
            if (StringUtils.isBlank(n.id())) {
                errors.add(new PlanValidationReport.ValidationError(
                    PlanValidationReport.ValidationError.Type.BLANK_NODE_ID,
                    null,
                    "节点ID为空",
                    "确保所有节点都有唯一ID"
                ));
            }
            if (StringUtils.isBlank(n.name())) {
                errors.add(new PlanValidationReport.ValidationError(
                    PlanValidationReport.ValidationError.Type.BLANK_NODE_NAME,
                    n.id(),
                    "节点名称为空: " + n.id(),
                    "为节点提供有意义的名称"
                ));
            } else if (isOperationOnlyNodeName(n.name())) {
                errors.add(new PlanValidationReport.ValidationError(
                        PlanValidationReport.ValidationError.Type.LOW_LEVEL_NODE_NAME,
                        n.id(),
                        "节点名称只描述了内部文件操作: " + n.name(),
                        "改成普通用户能理解的交付结果和用途；例如「生成用于解析航班信息的程序」，"
                                + "并把具体文件路径保留在 outputs 或 doneWhen.path 中"));
            }

            // 检查ID唯一性
            if (StringUtils.isNotBlank(n.id()) && !ids.add(n.id())) {
                errors.add(new PlanValidationReport.ValidationError(
                    PlanValidationReport.ValidationError.Type.DUPLICATE_IDS,
                    n.id(),
                    "节点ID重复: " + n.id(),
                    "修改重复的节点ID"
                ));
            }

            // 检查完成条件
            if (!n.doneWhen().valid()) {
                errors.add(new PlanValidationReport.ValidationError(
                    PlanValidationReport.ValidationError.Type.INVALID_DONE_WHEN,
                    n.id(),
                    "节点完成条件无效: " + n.id(),
                        n.doneWhen().isCommand()
                                ? "command_success 的 criteria 必须写明需要实际执行并验收的完整命令"
                                : "检查 doneWhen 配置"
                ));
            }

            // 规划模型可能凭经验擅自追加「写入文件」步骤。文件交付必须有
            // 用户原文证据；否则直接拒绝整张图，避免把搜索/问答变成隐式落盘。
            if (plan != null && !fileDeliveryAllowed && isFileDeliveryNode(n)) {
                errors.add(new PlanValidationReport.ValidationError(
                        PlanValidationReport.ValidationError.Type.UNREQUESTED_FILE_DELIVERY,
                        n.id(),
                        "用户未要求文件交付，却生成了文件落盘节点: " + n.name(),
                        "删除 file_write/file_exists；仅在用户明确要求保存、导出、写入文档/报告或给出输出路径时保留"
                ));
            }

            if (structured && DataflowNormalizer.needsFileAcceptance(n)
                    && n.doneWhen().isNote()) {
                errors.add(new PlanValidationReport.ValidationError(
                        PlanValidationReport.ValidationError.Type.INVALID_DONE_WHEN,
                        n.id(),
                        "落盘节点不能用 note_required: " + n.name(),
                        "doneWhen.type=file_exists 并填 path；"
                                + "出图可用 media_delivered"
                ));
            }

            String cap = n.capability() == null ? ""
                    : n.capability().trim().toLowerCase(Locale.ROOT);
            if (structured && !schedulableCapability(cap)) {
                errors.add(new PlanValidationReport.ValidationError(
                        PlanValidationReport.ValidationError.Type.CAPABILITY_MISMATCH,
                        n.id(),
                        "未知、空或 general capability: " + n.capability(),
                        "改成 file_read/file_write/web/code/image/browser/"
                                + "shell/research/deliver/plan，禁止 general"
                ));
            }

            if (structured && persistAcceptance(n.doneWhen())) {
                boolean acquireMix = CapabilityRegistry.acquires(cap);
                boolean fileOnNonWriter = n.doneWhen().isFile()
                        && CapabilityRegistry.knownCapability(cap)
                        && !CapabilityRegistry.writesFiles(cap);
                if (acquireMix || fileOnNonWriter) {
                    errors.add(new PlanValidationReport.ValidationError(
                            PlanValidationReport.ValidationError.Type.UNDER_DECOMPOSED,
                            n.id(),
                            acquireMix
                                    ? "获取能力不能挂落盘验收: " + cap
                                    : "file_exists 挂在不能写盘的能力上: " + cap,
                            "获取与落盘必须拆成两个节点并用 dependsOn 相连"
                    ));
                }
            }

            // 检查依赖存在性
            for (String d : n.dependsOn()) {
                if (g.byId(d) == null) {
                    errors.add(new PlanValidationReport.ValidationError(
                        PlanValidationReport.ValidationError.Type.MISSING_DEPENDENCY,
                        n.id(),
                        "节点依赖不存在: " + n.id() + " -> " + d,
                        "检查依赖节点ID是否正确"
                    ));
                }
            }

            // 检查输入产出
            if (!inputsProducedByDeps(g, n, externalInputs(intent, g))) {
                errors.add(new PlanValidationReport.ValidationError(
                    PlanValidationReport.ValidationError.Type.UNPRODUCED_INPUTS,
                    n.id(),
                    "节点输入未被前置节点产出: " + n.id(),
                    "确保输入由依赖链上的前序节点产出；用户消息/附件须在 intent.inputs 声明为外部输入"
                ));
            }
        }

        addCodeValidationError(g, plan, errors);
        addAcquirePersistEdgeError(g, errors);
        addGoalFileErrors(g, goal, errors);
        addGoalGraphSemanticErrors(g, plan, errors);

        // 4. 添加性能警告（非致命）
        if (g.nodes().size() > 10) {
            warnings.add(new PlanValidationReport.ValidationWarning(
                PlanValidationReport.ValidationWarning.Type.PERFORMANCE,
                null,
                "任务图节点较多(" + g.nodes().size() + ")，可能影响执行效率"
            ));
        }

        return new PlanValidationReport(errors, warnings);
    }

    static boolean isOperationOnlyNodeName(String name) {
        return StringUtils.isNotBlank(name) && (OPERATION_ONLY_NODE_NAME.matcher(name.trim()).matches()
                || EXECUTION_ONLY_NODE_NAME.matcher(name.trim()).matches()
                || TOOL_ACTION_ONLY_NODE_NAME.matcher(name.trim()).matches());
    }

    private static void addCodeValidationError(TaskGraph graph, TaskPlan plan,
                                               List<PlanValidationReport.ValidationError> errors) {
        if (plan == null || StringUtils.isBlank(plan.taskGoal())
                || !CodeValidationPolicy.isCodeChangeGoal(plan.taskGoal())) return;
        List<TaskNode> codeChanges = graph.nodes().stream()
                .filter(node -> "code".equalsIgnoreCase(node.capability())
                        || SOURCE_ARTIFACT.matcher(node.name()).find()
                        || node.outputs().stream().anyMatch(output -> SOURCE_ARTIFACT.matcher(output).find())
                        || (node.doneWhen() != null && SOURCE_ARTIFACT.matcher(node.doneWhen().path()).find()))
                .toList();
        if (codeChanges.isEmpty()) return;
        List<String> changePaths = codeChanges.stream()
                .flatMap(node -> java.util.stream.Stream.concat(
                        java.util.stream.Stream.concat(node.outputs().stream(),
                                java.util.stream.Stream.of(node.doneWhen() == null ? "" : node.doneWhen().path())),
                        java.util.stream.Stream.ofNullable(firstPathArgument(node))))
                .filter(path -> path != null && !path.isBlank())
                .toList();
        Set<String> changedModules = changePaths.stream()
                .map(CodeValidationPolicy::moduleHint)
                .filter(module -> !module.isBlank())
                .collect(java.util.stream.Collectors.toSet());
        boolean unknownAffectedModule = changePaths.isEmpty()
                || changePaths.stream().anyMatch(path -> CodeValidationPolicy.moduleHint(path).isBlank());
        boolean hasDependentVerification = graph.nodes().stream()
                .filter(node -> node.doneWhen() != null && node.doneWhen().isCommand()
                        && CodeValidationPolicy.isTestCommandForModules(
                                node.doneWhen().criteria(), changedModules, unknownAffectedModule))
                .anyMatch(verification -> codeChanges.stream().anyMatch(change ->
                        !verification.id().equals(change.id())
                                && dependsOnTransitively(graph, verification.id(), change.id())));
        if (!hasDependentVerification) {
            String nodeId = codeChanges.get(0).id();
            errors.add(new PlanValidationReport.ValidationError(
                    PlanValidationReport.ValidationError.Type.MISSING_CODE_VALIDATION,
                    nodeId,
                    "代码改动计划缺少依赖改动节点的测试步骤",
                    "新增独立 shell 测试节点，doneWhen 使用 command_success 并写明实际测试命令；"
                            + "只接受不会跳过测试或掩盖失败的测试运行器命令及其成功 exec_command 回执。"
                            + "命令需授权时先等待授权，不得跳过验证"));
        }
    }

    private static String firstPathArgument(TaskNode node) {
        for (String key : List.of("path", "filePath", "file_path", "file")) {
            Object value = node.toolArguments().get(key);
            if (value instanceof String path && !path.isBlank()) return path;
        }
        return null;
    }

    private static boolean dependsOnTransitively(TaskGraph graph, String nodeId, String ancestorId) {
        Set<String> visited = new HashSet<>();
        List<String> pending = new ArrayList<>();
        TaskNode node = graph.byId(nodeId);
        if (node != null) pending.addAll(node.dependsOn());
        while (!pending.isEmpty()) {
            String current = pending.remove(pending.size() - 1);
            if (!visited.add(current)) continue;
            if (ancestorId.equals(current)) return true;
            TaskNode dependency = graph.byId(current);
            if (dependency != null) pending.addAll(dependency.dependsOn());
        }
        return false;
    }

    /**
     * 调度器要能从 capability 选出工具面。general 等于没选。
     */
    static boolean schedulableCapability(String cap) {
        return StringUtils.isNotBlank(cap)
                && !CapabilityRegistry.GENERAL.equalsIgnoreCase(cap)
                && CapabilityRegistry.knownCapability(cap);
    }

    static boolean persistAcceptance(DoneWhen dw) {
        return dw != null && (dw.isFile() || dw.isMedia());
    }

    static boolean persistRole(TaskNode n) {
        if (n == null) {
            return false;
        }
        return CapabilityRegistry.persistsArtifacts(n.capability())
                || persistAcceptance(n.doneWhen());
    }

    static boolean isFileDeliveryNode(TaskNode n) {
        if (n == null) {
            return false;
        }
        String cap = n.capability() == null ? "" : n.capability().trim();
        return "file_write".equalsIgnoreCase(cap)
                || (n.doneWhen() != null && n.doneWhen().isFile());
    }

    /**
     * 读取/分析已有文件只表示输入，不是输出授权。plan 为空时保留旧 API
     * 行为；运行时 TaskPlan 总是非空，因而会受到硬约束保护。
     */
    static boolean fileDeliveryRequested(TaskPlan plan) {
        if (plan == null) {
            return true;
        }
        if (plan.artifactDeliveryRequested() != null) return plan.artifactDeliveryRequested();
        return claimsFileDelivery(plan.taskGoal());
    }

    /**
     * 一段文本本身是否在要求文件交付。用户原文与模型改写的 objective 共用这一条判据，
     * 避免「校验按原文、目标按改写」两套口径互相打架。
     */
    static boolean claimsFileDelivery(String text) {
        String t = text == null ? "" : text.trim();
        if (t.isBlank()) {
            return false;
        }
        boolean readOnly = READ_ONLY_ACTION.matcher(t).find()
                && !FILE_OUTPUT_ACTION.matcher(t).find();
        if (readOnly) {
            return false;
        }
        if (!FILE_OUTPUT_ACTION.matcher(t).find()) {
            return false;
        }
        // 保存/导出/写入本身已经是文件交付指令；生成/创建/写一个等动作
        // 仍要求明确目标，避免「写一首歌」被误当成落盘任务。
        return FILE_OUTPUT_TARGET.matcher(t).find()
                || UNAMBIGUOUS_SAVE_ACTION.matcher(t).find();
    }

    /**
     * 获取与落盘须有执行依赖。既允许获取→写入，也允许生成页面→浏览器验收。
     */
    static void addAcquirePersistEdgeError(
            TaskGraph g, List<PlanValidationReport.ValidationError> errors) {
        boolean hasAcquireOnly = false;
        boolean hasPersistOnly = false;
        TaskNode firstPersist = null;
        for (TaskNode n : g.nodes()) {
            boolean acquire = CapabilityRegistry.acquires(n.capability());
            boolean persist = persistRole(n);
            if (acquire && !persist) {
                hasAcquireOnly = true;
            }
            if (persist && !acquire) {
                hasPersistOnly = true;
                if (firstPersist == null) {
                    firstPersist = n;
                }
            }
        }
        if (!hasAcquireOnly || !hasPersistOnly) {
            return;
        }
        for (TaskNode n : g.nodes()) {
            if ((persistRole(n) && hasAcquireAncestor(g, n))
                    || (CapabilityRegistry.acquires(n.capability()) && hasAncestor(g, n, PlanValidator::persistRole))) {
                return;
            }
        }
        String id = firstPersist == null ? null : firstPersist.id();
        errors.add(new PlanValidationReport.ValidationError(
                PlanValidationReport.ValidationError.Type.UNDER_DECOMPOSED,
                id,
                "图中获取与落盘节点之间缺少执行依赖",
                "按实际数据流连接依赖，例如 web → file_write，或 code → browser 验收"
        ));
    }

    static boolean isDiagnosticGoal(String goal) {
        return goal != null && DIAGNOSTIC_GOAL.matcher(goal).find();
    }

    static void addGoalGraphSemanticErrors(TaskGraph g, TaskPlan plan,
            List<PlanValidationReport.ValidationError> errors) {
        if (plan == null || !isDiagnosticGoal(plan.taskGoal())) {
            return;
        }
        boolean hasDiagnosis = g.nodes().stream().anyMatch(n -> {
            String text = (n.name() + " " + n.capability()).toLowerCase(Locale.ROOT);
            // “错误/报错/故障”只描述证据或问题对象，不代表节点已经完成分析。
            // 必须出现明确的诊断动作，避免 file_read 节点“读取错误证据”绕过语义验收。
            return text.contains("诊断") || text.contains("定位") || text.contains("分析")
                    || text.contains("根因") || text.contains("原因分析")
                    || text.contains("root cause") || text.contains("diagnos")
                    || text.contains("debug");
        });
        boolean hasEvidence = g.nodes().stream().anyMatch(n -> {
            String capability = n.capability() == null ? "" : n.capability().toLowerCase(Locale.ROOT);
            String text = n.name() == null ? "" : n.name().toLowerCase(Locale.ROOT);
            return capability.contains("file_read") || capability.contains("web")
                    || capability.contains("browser") || capability.contains("code")
                    || text.contains("日志") || text.contains("响应");
        });
        if (!hasDiagnosis || !hasEvidence) {
            errors.add(new PlanValidationReport.ValidationError(
                    PlanValidationReport.ValidationError.Type.GOAL_GRAPH_MISMATCH,
                    null,
                    "用户目标包含错误诊断语义，但任务图没有证据获取和诊断节点",
                    "必须先获取或整理错误证据，再分析错误码/根因，最后给出修复和验证步骤"
            ));
        }
    }

    static void addGoalFileErrors(TaskGraph g, Goal goal,
            List<PlanValidationReport.ValidationError> errors) {
        if (goal == null || goal.successCriteria() == null) {
            return;
        }
        for (String c : goal.successCriteria()) {
            if (c == null || c.isBlank() || Goal.isPlaceholderCriterion(c) || StepEvaluator.hasMatchingJudge(g, c)) {
                continue;
            }
            String path = DataflowNormalizer.pathFromName(c);
            if (path.isBlank() || !path.equals(c.trim())) {
                errors.add(new PlanValidationReport.ValidationError(
                        PlanValidationReport.ValidationError.Type.GOAL_GRAPH_MISMATCH,
                        null, "successCriteria 缺少可执行验收，尚未验证: " + c,
                        "将完成条件原文绑定到节点 doneWhen.criteria 或 checks；文件存在条件只填写精确路径。"));
                continue;
            }
            if (StepEvaluator.hasFileDeliverable(g, path)) {
                continue;
            }
            errors.add(new PlanValidationReport.ValidationError(
                    PlanValidationReport.ValidationError.Type.UNDER_DECOMPOSED,
                    null,
                    "successCriteria 缺少产物 " + path,
                    "增加 file_exists 节点，path=" + path
            ));
        }
    }

    static boolean hasAcquireAncestor(TaskGraph g, TaskNode node) {
        return hasAncestor(g, node, n -> CapabilityRegistry.acquires(n.capability()));
    }

    private static boolean hasAncestor(TaskGraph g, TaskNode node, java.util.function.Predicate<TaskNode> predicate) {
        if (g == null || node == null) {
            return false;
        }
        Set<String> seen = new HashSet<>();
        List<String> queue = new ArrayList<>(node.dependsOn());
        int i = 0;
        while (i < queue.size()) {
            String id = queue.get(i++);
            if (id == null || !seen.add(id)) {
                continue;
            }
            TaskNode dep = g.byId(id);
            if (dep == null) {
                continue;
            }
            if (predicate.test(dep)) {
                return true;
            }
            queue.addAll(dep.dependsOn());
        }
        return false;
    }

    /**
     * 验证并生成修正提示（用于 replan）
     */
    public String generateCorrectionPrompt(TaskGraph g, TaskPlan plan) {
        PlanValidationReport report = validate(g, plan);
        if (report.valid()) {
            return null;
        }
        return report.toCorrectionPrompt();
    }

    static boolean inputsProducedByDeps(TaskGraph g, TaskNode n) {
        return inputsProducedByDeps(g, n, Set.of());
    }

    private static Set<String> externalInputs(TaskIntent intent, TaskGraph graph) {
        if (intent == null) return Set.of();
        Set<String> external = new HashSet<>();
        for (TaskIntent.Input input : intent.inputs()) {
            if (StringUtils.isNotBlank(input.name()) && StringUtils.isNotBlank(input.source()))
                external.add(input.name());
        }
        // A graph output cannot be relabelled as an external input to bypass its dependency edge.
        for (TaskNode node : graph.nodes()) external.removeAll(node.outputs());
        return external;
    }

    private static boolean inputsProducedByDeps(TaskGraph g, TaskNode n, Set<String> external) {
        Set<String> available = new HashSet<>(external);
        Set<String> visited = new HashSet<>();
        java.util.ArrayDeque<String> pending = new java.util.ArrayDeque<>(n.dependsOn());
        while (!pending.isEmpty()) {
            String id = pending.removeFirst();
            if (!visited.add(id) || id.equals(n.id())) continue;
            TaskNode ancestor = g.byId(id);
            if (ancestor == null) continue;
            available.addAll(ancestor.outputs());
            pending.addAll(ancestor.dependsOn());
        }
        for (String in : n.inputs()) {
            if (!available.contains(in)) return false;
        }
        return true;
    }
}
