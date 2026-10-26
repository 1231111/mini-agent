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
    /**
     * 单任务累计 token 的<b>告警线</b>（不是终止线）。
     *
     * <p>语义变更说明：这个值原名 {@code max-estimated-tokens}，到顶会直接结束任务。
     * 但累计 token 随任务时长线性增长，正常的 90 轮长任务和一次死循环在这条线上无法区分，
     * 于是长任务被误杀。现在它只触发一次告警与轨迹记录，任务继续。
     * 真正拦失控的是 {@code deadlineMs}、{@code maxToolCalls}、{@code maxIterations}
     * 与重复工具检测 —— 这四道都与工作量无关。</p>
     */
    private long tokenSoftLimit = 8_000_000L;
    /** 直跑循环上限；调用方传入值不得高于此。 */
    private int maxIterations = 90;
    /** delegate_task 内层循环上限。 */
    private int subagentMaxIterations = 25;

    public ExecutionProperties() {
    }

    public ExecutionProperties(long deadlineMs, int maxToolCalls, long tokenSoftLimit) {
        this.deadlineMs = deadlineMs;
        this.maxToolCalls = maxToolCalls;
        this.tokenSoftLimit = tokenSoftLimit;
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

    public long getTokenSoftLimit() {
        return tokenSoftLimit;
    }

    public void setTokenSoftLimit(long tokenSoftLimit) {
        this.tokenSoftLimit = tokenSoftLimit;
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
