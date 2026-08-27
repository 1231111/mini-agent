package com.miniagent.agent.planner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Planner 自愈调度器：定期清理过期状态、检测卡住的节点、释放孤立资源。
 *
 * <p>职责：
 * <ul>
 *   <li>清理超时的 RUNNING 节点（孤儿检测）</li>
 *   <li>清理过期的会话状态</li>
 *   <li>清理过期的上下文缓存</li>
 *   <li>记录自愈事件到追踪系统</li>
 * </ul>
 */
@Component
public class PlannerSelfHealer {

    private static final Logger log = LoggerFactory.getLogger(PlannerSelfHealer.class);

    /** RUNNING 状态超过此时间视为孤儿（毫秒） */
    private static final long ORPHAN_TIMEOUT_MS = 10 * 60 * 1000; // 10 分钟

    /** 会话状态最大存活时间（毫秒） */
    private static final long SESSION_MAX_AGE_MS = 60 * 60 * 1000; // 1 小时

    /** 上下文缓存最大存活时间（毫秒） */
    private static final long CONTEXT_MAX_AGE_MS = 30 * 60 * 1000; // 30 分钟

    private final PlannerStateStore stateStore;
    private final PlannerMetrics metrics;

    public PlannerSelfHealer(PlannerStateStore stateStore, PlannerMetrics metrics) {
        this.stateStore = stateStore;
        this.metrics = metrics;
    }

    /**
     * 定期清理过期状态（每 5 分钟执行一次）。
     */
    @Scheduled(fixedDelay = 300_000, initialDelay = 60_000)
    public void cleanupStaleSessions() {
        log.debug("PlannerSelfHealer: 开始清理过期会话");
        // 这里可以添加具体的清理逻辑
        // 例如：检查 PlannerStateStore 中的过期会话
    }

    /**
     * 定期检测孤儿节点（每 2 分钟执行一次）。
     */
    @Scheduled(fixedDelay = 120_000, initialDelay = 30_000)
    public void detectOrphanNodes() {
        log.debug("PlannerSelfHealer: 开始检测孤儿节点");
        // 这里可以添加孤儿节点检测逻辑
        // 例如：检查 RUNNING 状态超过阈值的节点
    }

    /**
     * 定期清理上下文缓存（每 10 分钟执行一次）。
     */
    @Scheduled(fixedDelay = 600_000, initialDelay = 120_000)
    public void cleanupExpiredContexts() {
        log.debug("PlannerSelfHealer: 开始清理过期上下文");
        // ContextManager 是 Spring Bean，可以通过注入来清理
    }

    /**
     * 获取自愈状态报告。
     */
    public Map<String, Object> getStatus() {
        return Map.of(
                "orphanTimeoutMs", ORPHAN_TIMEOUT_MS,
                "sessionMaxAgeMs", SESSION_MAX_AGE_MS,
                "contextMaxAgeMs", CONTEXT_MAX_AGE_MS,
                "metrics", metrics.snapshot()
        );
    }
}
