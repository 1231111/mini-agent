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

/**
 * TaskGraph 结构验收：无环、id 唯一、依赖存在、inputs 由前置 outputs 产出。
 * 支持返回结构化验证报告，用于 replan 闭环修正
 */
@Component
public class PlanValidator {

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

        // 3. 检查节点属性
        boolean structured = plan != null && plan.requiresStructuredPlan();
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

        if (structured) {
            addAcquirePersistEdgeError(g, errors);
            addGoalFileErrors(g, goal, errors);
        }

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
