package com.miniagent.agent.task;

import com.miniagent.common.MessageConstants;
import org.apache.commons.lang3.StringUtils;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 本轮的输入参数：用户在说什么、允许哪些工具、是否有预置步骤。
 *
 * <p>不再包含「本轮属于哪一类」的分类结果，也不包含从用户文本里用词表推断出的信号。
 * 是否拆解、是否建 todo、调哪个工具，全部由模型在 {@code AgentLoop} 内自行决定。</p>
 *
 * <p>历史说明：这里曾有三个字段（{@code intent} / {@code decision} /
 * {@code directExecutable}），后来又有一组 {@code TaskSignals}
 * 与 {@code requiresStructuredPlan}。分类结果先被删除；信号层在
 * 2026-10-22 一并删除，因为它把「文本里出现了哪个词」变成了执行路径的分支条件。</p>
 */
public record TaskPlan(
        String taskGoal,
        List<String> allowedTools,
        List<TaskStep> steps,
        String reason
) {

    public TaskPlan {
        taskGoal = taskGoal == null ? "" : taskGoal;
        reason = reason == null ? "" : reason;
        steps = steps == null ? List.of() : List.copyOf(steps);
    }

    /** 常规构造：不限制工具、无预设步骤。 */
    public static TaskPlan of(String taskGoal) {
        return new TaskPlan(taskGoal, null, List.of(), "");
    }

    public TaskPlan withTaskGoal(String value) {
        return new TaskPlan(value, allowedTools, steps, reason);
    }

    /** {@code null} 表示不限制（全量工具）。 */
    public Set<String> allowedToolSet() {
        return Objects.isNull(allowedTools) ? null : new LinkedHashSet<>(allowedTools);
    }

    public String toPromptBlock() {
        return toPromptBlock(false);
    }

    public String toPromptBlock(boolean graphOwned) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 本轮任务参数\n");
        sb.append("- taskGoal: ").append(taskGoal).append('\n');
        sb.append("- allowedTools: ")
                .append(Optional.ofNullable(allowedTools).orElse(List.of())).append('\n');
        if (StringUtils.isNotBlank(reason)) {
            sb.append("- reason: ").append(reason).append('\n');
        }
        if (!steps.isEmpty()) {
            sb.append("## 计划步骤\n");
            for (TaskStep step : steps) {
                sb.append(step.id()).append(". ")
                        .append(step.goal())
                        .append(" | tools=")
                        .append(Objects.isNull(step.allowedTools()) ? List.of() : step.allowedTools())
                        .append('\n');
            }
        }
        if (graphOwned) {
            sb.append("\n").append(MessageConstants.TASK_GRAPH_OWNED);
        } else {
            sb.append("\n直接执行用户目标并给出结果。不要为凑数拆 todo；一步能做完就一步做完。");
        }
        sb.append("如果对话历史里有未完成的任务、且用户本轮在要求继续，就接着上次的进度做，不要从头重来；")
                .append("否则按当前用户消息执行，不要把已完成的历史任务当成当前任务重做。");
        return sb.toString();
    }
}
