package com.miniagent.agent.planner;

import com.miniagent.agent.task.TaskPlan;
import com.miniagent.agent.tool.CapabilityRegistry;
import org.apache.commons.lang3.StringUtils;
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

    public PlanValidationReport validate(TaskGraph g, TaskPlan plan) {
        return validate(g, plan, null);
    }

    /**
     * 结构化验证。goal 非空时核对 successCriteria 里的文件是否在图上。
     */
    public PlanValidationReport validate(TaskGraph g, TaskPlan plan, Goal goal) {
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

        // 2. 检查循环依赖
        if (g.hasCycle()) {
            errors.add(new PlanValidationReport.ValidationError(
                PlanValidationReport.ValidationError.Type.HAS_CYCLE,
                null,
                "任务图包含循环依赖",
                "检查节点依赖关系，确保无环"
            ));
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
                    "检查 doneWhen 配置"
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
            if (!inputsProducedByDeps(g, n)) {
                errors.add(new PlanValidationReport.ValidationError(
                    PlanValidationReport.ValidationError.Type.UNPRODUCED_INPUTS,
                    n.id(),
                    "节点输入未被前置节点产出: " + n.id(),
                    "确保依赖节点的outputs包含当前节点的inputs"
                ));
            }
        }

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
     * ponytail: 图里同时有获取和落盘时，至少一条获取→落盘边。
     * 要求每个落盘都挂获取会误伤「并行写本地文件」。
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
            if (persistRole(n) && hasAcquireAncestor(g, n)) {
                return;
            }
        }
        String id = firstPersist == null ? null : firstPersist.id();
        errors.add(new PlanValidationReport.ValidationError(
                PlanValidationReport.ValidationError.Type.UNDER_DECOMPOSED,
                id,
                "图中同时有获取和落盘，但没有 dependsOn 边",
                "落盘节点必须依赖获取节点，例如 web → file_write"
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
            if (Goal.isPlaceholderCriterion(c)) {
                continue;
            }
            String path = DataflowNormalizer.pathFromName(c);
            if (path.isBlank() || StepEvaluator.hasFileDeliverable(g, path)) {
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
            if (CapabilityRegistry.acquires(dep.capability())) {
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
        for (String in : n.inputs()) {
            boolean found = false;
            for (String d : n.dependsOn()) {
                TaskNode dep = g.byId(d);
                if (dep != null && dep.outputs().contains(in)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
        }
        return true;
    }
}
