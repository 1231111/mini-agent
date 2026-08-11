package com.miniagent.agent.intent;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Objects;
import java.util.Optional;
import org.apache.commons.lang3.StringUtils;

public record TaskPlan(
        IntentType intent,
        String taskGoal,
        boolean directExecutable,
        boolean shouldUseHistory,
        boolean needsTools,
        List<String> allowedTools,
        List<TaskStep> steps,
        String reason,
        /** 复杂任务：进入 AgentLoop 后必须先 todo.set，否则框架只放行 todo 工具 */
        boolean requiresStructuredPlan
) {
    /** 兼容旧 8 参构造：默认不强制结构化计划 */
    public TaskPlan(IntentType intent, String taskGoal, boolean directExecutable,
                    boolean shouldUseHistory, boolean needsTools,
                    List<String> allowedTools, List<TaskStep> steps, String reason) {
        this(intent, taskGoal, directExecutable, shouldUseHistory, needsTools,
                allowedTools, steps, reason, false);
    }

    /**
     * null = 不限制（注册表全量工具）；空集合 = 无工具。
     */
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
            sb.append("\n【强制】这是复杂任务：你的第一轮工具调用必须是 todo(action=set)，")
              .append("为每一步写清 content 与 done_when（验收标准，如 file_exists:workspace/xxx.md）。")
              .append("未完成全部 todo 前禁止最终收尾。批量独立产出必须用 delegate_task 并行派发。");
        } else {
            sb.append("\n复杂任务（>=3步）自己用 todo 工具拆解并逐项推进，每完成一项标记 completed，全部完成再收尾。");
        }
        sb.append("如果对话历史里有未完成的任务、且用户本轮在要求继续，就接着上次的进度做，不要从头重来；")
          .append("否则按当前用户消息执行，不要把已完成的历史任务当成当前任务重做。");
        return sb.toString();
    }
}
