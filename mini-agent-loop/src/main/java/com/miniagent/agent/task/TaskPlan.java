package com.miniagent.agent.task;

import com.miniagent.common.MessageConstants;
import org.apache.commons.lang3.StringUtils;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 本轮任务的输入参数。
 *
 * <p>字段只有两类：用户在说什么（{@code taskGoal}）、以及从消息里观察到的事实
 * （{@code signals}）。不含任何「本轮属于哪一类」的分类结果，因此不会出现
 * 一个值同时决定工具面、上下文与执行路径的情况。</p>
 *
 * <p>原先这里还有三个字段（{@code intent} / {@code decision} / {@code directExecutable}
 * / {@code shouldUseHistory} / {@code needsTools}），其中分类结果已删除，
 * 另外几个经全量检索确认没有任何读取方，一并删除。</p>
 */
public record TaskPlan(
        String taskGoal,
        List<String> allowedTools,
        List<TaskStep> steps,
        String reason,
        boolean requiresStructuredPlan,
        TaskSignals signals
) {

    public TaskPlan {
        taskGoal = taskGoal == null ? "" : taskGoal;
        reason = reason == null ? "" : reason;
        steps = steps == null ? List.of() : List.copyOf(steps);
        signals = signals == null ? TaskSignals.NONE : signals;
    }

    /** 常规构造：不限制工具、无预设步骤。 */
    public static TaskPlan of(String taskGoal, boolean requiresStructuredPlan, TaskSignals signals) {
        return new TaskPlan(taskGoal, null, List.of(), "", requiresStructuredPlan, signals);
    }

    public TaskPlan withTaskGoal(String value) {
        return new TaskPlan(value, allowedTools, steps, reason, requiresStructuredPlan, signals);
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
        sb.append("- requiresStructuredPlan: ").append(requiresStructuredPlan).append('\n');
        sb.append("- observedSignals: ").append(signals.describe()).append('\n');
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
        if (signals.lightTurn()) {
            sb.append("\n【轻问答】直接用中文简洁回答用户。")
                    .append("优先依据系统提示中的能力说明；仅当需要核对已安装技能时才调 skill_list/skill_view。")
                    .append("禁止调用文件/终端/浏览器/生图等执行类工具；不要建 todo；不要开多轮探索。");
            return sb.toString();
        }
        if (graphOwned) {
            sb.append("\n").append(MessageConstants.TASK_GRAPH_OWNED);
        } else if (requiresStructuredPlan) {
            sb.append("\n【强制】复杂任务。若已有 todo 清单，禁止 todo.set/clear，")
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
