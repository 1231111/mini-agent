package com.miniagent.agent.core;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 一次运行的引擎预算。规划器节点上限仍走 {@code agent.planner.*}，
 * 那是图策略，不是循环内核。
 */
@ConfigurationProperties(prefix = "agent.execution")
public class ExecutionProperties {

    private long deadlineMs = 1_800_000L;
    private int maxToolCalls = 120;
    private long maxEstimatedTokens = 2_000_000L;
    /** 直跑循环上限；调用方传入值不得高于此。 */
    private int maxIterations = 90;
    /** delegate_task 内层循环上限。 */
    private int subagentMaxIterations = 25;

    public ExecutionProperties() {
    }

    public ExecutionProperties(long deadlineMs, int maxToolCalls, long maxEstimatedTokens) {
        this.deadlineMs = deadlineMs;
        this.maxToolCalls = maxToolCalls;
        this.maxEstimatedTokens = maxEstimatedTokens;
    }

    public long getDeadlineMs() {
        return deadlineMs;
    }

    public void setDeadlineMs(long deadlineMs) {
        this.deadlineMs = deadlineMs;
    }

    public int getMaxToolCalls() {
        return maxToolCalls;
    }

    public void setMaxToolCalls(int maxToolCalls) {
        this.maxToolCalls = maxToolCalls;
    }

    public long getMaxEstimatedTokens() {
        return maxEstimatedTokens;
    }

    public void setMaxEstimatedTokens(long maxEstimatedTokens) {
        this.maxEstimatedTokens = maxEstimatedTokens;
    }

    public int getMaxIterations() {
        return maxIterations;
    }

    public void setMaxIterations(int maxIterations) {
        this.maxIterations = maxIterations;
    }

    public int getSubagentMaxIterations() {
        return subagentMaxIterations;
    }

    public void setSubagentMaxIterations(int subagentMaxIterations) {
        this.subagentMaxIterations = subagentMaxIterations;
    }

    public int capIterations(int requested) {
        int ceiling = Math.max(1, maxIterations);
        if (requested <= 0) {
            return ceiling;
        }
        return Math.min(requested, ceiling);
    }

    public int capSubagentIterations(int requested) {
        int ceiling = Math.max(1, subagentMaxIterations);
        if (requested <= 0) {
            return ceiling;
        }
        return Math.min(requested, ceiling);
    }
}
