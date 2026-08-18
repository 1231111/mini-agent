package com.miniagent.agent.intent;

import org.apache.commons.lang3.StringUtils;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public record TaskPlan(
        IntentType intent,
        String taskGoal,
        boolean directExecutable,
        boolean shouldUseHistory,
        boolean needsTools,
        List<String> allowedTools,
        List<TaskStep> steps,
        String reason,
        boolean requiresStructuredPlan,
        IntentDecision decision
) {
    public TaskPlan(IntentType intent, String taskGoal, boolean directExecutable,
                    boolean shouldUseHistory, boolean needsTools,
                    List<String> allowedTools, List<TaskStep> steps, String reason) {
        this(intent, taskGoal, directExecutable, shouldUseHistory, needsTools,
                allowedTools, steps, reason, false, null);
    }

    public TaskPlan(IntentType intent, String taskGoal, boolean directExecutable,
                    boolean shouldUseHistory, boolean needsTools,
                    List<String> allowedTools, List<TaskStep> steps, String reason,
                    boolean requiresStructuredPlan) {
        this(intent, taskGoal, directExecutable, shouldUseHistory, needsTools,
                allowedTools, steps, reason, requiresStructuredPlan, null);
    }

    public TaskPlan withDecision(IntentDecision value) {
        return new TaskPlan(intent, taskGoal, directExecutable, shouldUseHistory, needsTools,
                allowedTools, steps, reason, requiresStructuredPlan, value);
    }

    public Set<String> allowedToolSet() {
        return Objects.isNull(allowedTools) ? null : new LinkedHashSet<>(allowedTools);
    }

    public String toPromptBlock() {
        StringBuilder sb = new StringBuilder();
        sb.append("# 本轮意图识别与执行计划\n");
        sb.append("- intent: ").append(intent).append('\n');
        sb.append("- taskGoal: ").append(taskGoal).append('\n');
        sb.append("- directExecutable: ").append(directExecutable).append('\n');
        sb.append("- shouldUseHistory: ").append(shouldUseHistory).append('\n');
        sb.append("- needsTools: ").append(needsTools).append('\n');
        sb.append("- requiresStructuredPlan: ").append(requiresStructuredPlan).append('\n');
        if (decision != null) {
            sb.append("- intentConfidence: ").append(String.format("%.3f", decision.confidence())).append('\n');
            sb.append("- intentRisk: ").append(decision.riskLevel()).append('\n');
            sb.append("- intentSource: ").append(decision.source()).append('\n');
            sb.append("- needClarification: ").append(decision.needClarification()).append('\n');
        }
        sb.append("- allowedTools: ").append(Optional.ofNullable(allowedTools).orElse(List.of())).append('\n');
        if (StringUtils.isNotBlank(reason)) {
            sb.append("- reason: ").append(reason).append('\n');
        }
        if (Objects.nonNull(steps) && !steps.isEmpty()) {
            sb.append("## 计划步骤\n");
            for (TaskStep step : steps) {
                sb.append(step.id()).append(". ")
                        .append(step.goal())
                        .append(" | tools=")
                        .append(Objects.isNull(step.allowedTools()) ? List.of() : step.allowedTools())
                        .append('\n');
            }
        }
        if (intent == IntentType.QUESTION) {
            sb.append("\n【轻问答模式】直接用中文简洁回答用户。")
              .append("优先依据系统提示中的能力说明；仅当需要核对已安装技能时才调 skill_list/skill_view。")
              .append("禁止调用文件/终端/浏览器/生图等执行类工具；不要建 todo；不要开多轮探索。");
            return sb.toString();
        }
        if (requiresStructuredPlan) {
            sb.append("\n【强制】复杂任务。若已有 todo 清单（含规划器投影），禁止 todo.set/clear，")
              .append("只 update 当前子目标；仅清单为空时才 todo.set。")
              .append("未完成全部 todo 前禁止最终收尾。");
        } else {
            sb.append("\n直接执行用户目标并给出结果。不要为凑数拆 todo；一步能做完就一步做完。");
        }
        sb.append("如果对话历史里有未完成的任务、且用户本轮在要求继续，就接着上次的进度做，不要从头重来；")
          .append("否则按当前用户消息执行，不要把已完成的历史任务当成当前任务重做。");
        return sb.toString();
    }
}
