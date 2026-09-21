package com.miniagent.agent.planner;

import java.util.List;
import java.util.Map;

/**
 * 结构化目标：任务信号下游、TaskGraph 上游。
 *
 * <p>{@code signals} 是本轮从用户消息里观察到的事实清单（逗号分隔），
 * 由 {@link com.miniagent.agent.task.TaskSignals#describe()} 产出，只作审计与提示用，
 * 不参与任何分支判断 —— 需要判断的地方都直接读对应的那个信号。</p>
 */
public record Goal(
        String goalId,
        String objective,
        String signals,
        String taskType,
        Map<String, String> entities,
        List<String> constraints,
        List<String> successCriteria
) {
    /** 缺交付路径/来源时的澄清图，用户补参后重编译。 */
    public static final String TASK_TYPE_CLARIFY = "clarify";

    public Goal {
        signals = signals == null ? "" : signals;
        entities = entities == null ? Map.of() : Map.copyOf(entities);
        constraints = constraints == null ? List.of() : List.copyOf(constraints);
        successCriteria = successCriteria == null ? List.of() : List.copyOf(successCriteria);
    }

    public boolean isClarify() {
        return TASK_TYPE_CLARIFY.equals(taskType);
    }

    public static boolean isPlaceholderCriterion(String c) {
        return c != null && c.contains("完成 objective");
    }

    /** 不含 taskType 的构造函数。 */
    public Goal(String goalId, String objective, String signals,
                Map<String, String> entities, List<String> constraints,
                List<String> successCriteria) {
        this(goalId, objective, signals, null, entities, constraints, successCriteria);
    }
}
