package com.miniagent.agent.planner;

import com.miniagent.agent.intent.TaskPlan;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
        return validate(g, plan).valid();
    }

    /**
     * 结构化验证方法，返回详细的验证报告
     * @param g 任务图
     * @param plan 任务计划
     * @return 包含错误、警告和修正建议的验证报告
     */
    public PlanValidationReport validate(TaskGraph g, TaskPlan plan) {
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
