package com.miniagent.agent.planner;

import java.util.Map;

/**
 * 恢复策略配置，定义不同失败类型的恢复行为。
 *
 * <p>策略组成：
 * <ul>
 *   <li><b>诊断规则</b>：如何识别失败类型</li>
 *   <li><b>恢复动作</b>：具体执行什么恢复操作</li>
 *   <li><b>熔断条件</b>：何时停止恢复尝试</li>
 *   <li><b>降级方案</b>：恢复失败时的备选方案</li>
 * </ul>
 *
 * <p>策略优先级：
 * <ol>
 *   <li>精确匹配（FailureKind）</li>
 *   <li>模式匹配（正则/关键词）</li>
 *   <li>默认策略（GENERIC）</li>
 * </ol>
 */
public record RecoveryStrategy(
        /** 策略名称 */
        String name,
        /** 策略描述 */
        String description,
        /** 匹配的失败类型 */
        FailureKind matchKind,
        /** 匹配的失败类 */
        FailureClass matchClass,
        /** 最大重试次数 */
        int maxRetries,
        /** 重试间隔（毫秒） */
        long retryDelayMs,
        /** 是否启用指数退避 */
        boolean exponentialBackoff,
        /** 降级方案（恢复失败时） */
        DegradationAction degradationAction,
        /** 自定义恢复动作 */
        RecoveryAction customAction
) {

    /**
     * 降级方案。
     */
    public enum DegradationAction {
        /** 跳过当前节点，继续执行后续节点 */
        SKIP_NODE,
        /** 使用简化版本完成任务 */
        USE_SIMPLIFIED,
        /** 返回部分结果 */
        RETURN_PARTIAL,
        /** 终止任务并报告错误 */
        ABORT_WITH_ERROR,
        /** 人工介入 */
        HUMAN_INTERVENTION
    }

    /**
     * 自定义恢复动作接口。
     */
    @FunctionalInterface
    public interface RecoveryAction {
        /**
         * 执行恢复动作。
         *
         * @param node     失败的节点
         * @param diagnosis 失败诊断
         * @return 恢复后的节点（null 表示恢复失败）
         */
        TaskNode recover(TaskNode node, FailureDiagnosis diagnosis);
    }

    // ===== 预定义策略 =====

    /** 参数错误：修正参数后重试 */
    public static final RecoveryStrategy PARAM_ERROR = new RecoveryStrategy(
            "param_error_fix",
            "修正参数后重试同一工具",
            FailureKind.PARAM_ERROR,
            FailureClass.LOCAL_REPAIR,
            2, 1000, false,
            DegradationAction.USE_SIMPLIFIED,
            null
    );

    /** 未知工具：更换同类工具 */
    public static final RecoveryStrategy UNKNOWN_TOOL = new RecoveryStrategy(
            "unknown_tool_replace",
            "更换同类能力工具",
            FailureKind.UNKNOWN_TOOL,
            FailureClass.REPLACE_TOOL,
            3, 500, false,
            DegradationAction.USE_SIMPLIFIED,
            null
    );

    /** 超时：增加超时时间或拆分任务 */
    public static final RecoveryStrategy TIMEOUT = new RecoveryStrategy(
            "timeout_extend",
            "增加超时时间或拆分任务",
            FailureKind.TIMEOUT,
            FailureClass.LOCAL_REPAIR,
            2, 2000, true,
            DegradationAction.USE_SIMPLIFIED,
            null
    );

    /** 偏离目标：重写任务子图 */
    public static final RecoveryStrategy DRIFT = new RecoveryStrategy(
            "drift_rewrite",
            "重写任务子图以纠正偏离",
            FailureKind.DRIFT,
            FailureClass.REWRITE_GRAPH,
            1, 1000, false,
            DegradationAction.RETURN_PARTIAL,
            null
    );

    /** 验收失败：修改验收标准或重试 */
    public static final RecoveryStrategy EVAL_FAILED = new RecoveryStrategy(
            "eval_failed_retry",
            "修改验收标准或重试",
            FailureKind.EVAL_FAILED,
            FailureClass.LOCAL_REPAIR,
            2, 1000, false,
            DegradationAction.RETURN_PARTIAL,
            null
    );

    /** 资源耗尽：降级处理 */
    public static final RecoveryStrategy RESOURCE_EXHAUSTED = new RecoveryStrategy(
            "resource_degrade",
            "降级处理，使用简化方案",
            FailureKind.RESOURCE_EXHAUSTED,
            FailureClass.REWRITE_GRAPH,
            1, 5000, false,
            DegradationAction.USE_SIMPLIFIED,
            null
    );

    /** 权限拒绝：修订目标 */
    public static final RecoveryStrategy PERMISSION_DENIED = new RecoveryStrategy(
            "permission_revise",
            "修订目标，移除需要权限的操作",
            FailureKind.PERMISSION_DENIED,
            FailureClass.REVISE_GOAL,
            0, 0, false,
            DegradationAction.SKIP_NODE,
            null
    );

    /** 默认策略 */
    public static final RecoveryStrategy DEFAULT = new RecoveryStrategy(
            "default_retry",
            "默认重试策略",
            FailureKind.GENERIC,
            FailureClass.LOCAL_REPAIR,
            1, 1000, false,
            DegradationAction.ABORT_WITH_ERROR,
            null
    );

    /**
     * 策略注册表，按 FailureKind 索引。
     * 注意：Java Map.of() 最多支持10对，超过需用 HashMap。
     */
    private static final Map<FailureKind, RecoveryStrategy> STRATEGY_MAP;
    static {
        var map = new java.util.HashMap<FailureKind, RecoveryStrategy>();
        map.put(FailureKind.PARAM_ERROR, PARAM_ERROR);
        map.put(FailureKind.UNKNOWN_TOOL, UNKNOWN_TOOL);
        map.put(FailureKind.TIMEOUT, TIMEOUT);
        map.put(FailureKind.DRIFT, DRIFT);
        map.put(FailureKind.EVAL_FAILED, EVAL_FAILED);
        map.put(FailureKind.RESOURCE_EXHAUSTED, RESOURCE_EXHAUSTED);
        map.put(FailureKind.PERMISSION_DENIED, PERMISSION_DENIED);
        map.put(FailureKind.GENERIC, DEFAULT);
        map.put(FailureKind.TOOL_ERROR, DEFAULT);
        map.put(FailureKind.NO_READY, DRIFT);
        map.put(FailureKind.GOAL_BLOCKED, PERMISSION_DENIED);
        map.put(FailureKind.HARD_GATE, PARAM_ERROR);
        map.put(FailureKind.ORPHAN_RUNNING, DEFAULT);
        map.put(FailureKind.CONFLICT, TIMEOUT);
        map.put(FailureKind.DEPENDENCY_FAILED, TIMEOUT);
        STRATEGY_MAP = java.util.Map.copyOf(map);
    }

    /**
     * 根据失败类型获取恢复策略。
     */
    public static RecoveryStrategy forFailureKind(FailureKind kind) {
        return STRATEGY_MAP.getOrDefault(kind, DEFAULT);
    }

    /**
     * 判断是否应该重试。
     */
    public boolean shouldRetry(int currentRetryCount) {
        return currentRetryCount < maxRetries;
    }

    /**
     * 计算重试延迟（支持指数退避）。
     */
    public long calculateRetryDelay(int retryCount) {
        if (retryCount <= 0) {
            return retryDelayMs;
        }
        if (!exponentialBackoff) {
            return retryDelayMs;
        }
        int shift = Math.min(retryCount, 30);
        long multiplier = 1L << shift;
        if (retryCount >= 63 || retryDelayMs > 30_000L / multiplier) {
            return 30_000L;
        }
        return Math.min(retryDelayMs * multiplier, 30_000L); // 最大 30 秒
    }
}
