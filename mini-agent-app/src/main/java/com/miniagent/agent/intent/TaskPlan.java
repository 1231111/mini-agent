package com.miniagent.agent.intent;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public record TaskPlan(
        IntentType intent,
        String taskGoal,
        boolean directExecutable,
        boolean shouldUseHistory,
        boolean needsTools,
        List<String> allowedTools,
        List<TaskStep> steps,
        String reason
) {
    public Set<String> allowedToolSet() {
        return allowedTools == null ? Set.of() : new LinkedHashSet<>(allowedTools);
    }

    public String toPromptBlock() {
        StringBuilder sb = new StringBuilder();
        sb.append("# 本轮意图识别与执行计划\n");
        sb.append("- intent: ").append(intent).append('\n');
        sb.append("- taskGoal: ").append(taskGoal).append('\n');
        sb.append("- directExecutable: ").append(directExecutable).append('\n');
        sb.append("- shouldUseHistory: ").append(shouldUseHistory).append('\n');
        sb.append("- needsTools: ").append(needsTools).append('\n');
        sb.append("- allowedTools: ").append(allowedTools == null ? List.of() : allowedTools).append('\n');
        if (reason != null && !reason.isBlank()) {
            sb.append("- reason: ").append(reason).append('\n');
        }
        if (steps != null && !steps.isEmpty()) {
            sb.append("## 计划步骤\n");
            for (TaskStep step : steps) {
                sb.append(step.id()).append(". ")
                        .append(step.goal())
                        .append(" | tools=")
                        .append(step.allowedTools() == null ? List.of() : step.allowedTools())
                        .append('\n');
            }
        }
        sb.append("\n复杂任务（>=3步）自己用 todo 工具拆解并逐项推进，每完成一项标记 completed，全部完成再收尾。")
          .append("如果对话历史里有未完成的任务、且用户本轮在要求继续，就接着上次的进度做，不要从头重来；")
          .append("否则按当前用户消息执行，不要把已完成的历史任务当成当前任务重做。");
        return sb.toString();
    }
}
