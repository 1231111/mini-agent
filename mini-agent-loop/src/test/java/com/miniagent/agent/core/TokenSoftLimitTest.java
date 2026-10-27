package com.miniagent.agent.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 累计 token 的「只告警、不终止」语义。
 *
 * <p>改造前 {@code afterModelTokens} 在累计值越过上限时返回
 * {@code TOKEN_BUDGET_EXCEEDED}，主循环据此结束任务。问题是累计 token 度量的是
 * 「这个任务花了多少」，它随任务时长线性增长，与「任务是否失控」没有因果关系 ——
 * 正常的长任务和原地打转的死循环在这条线上长得一样，于是长任务被误杀。</p>
 *
 * <p>现在累计值只做两件事：统计展示、越过 {@code tokenSoftLimit} 时告警一次。
 * 这个类钉住四件事：越线不停、告警只出一次、新任务归零、input 快照是覆盖而非累加。</p>
 */
class TokenSoftLimitTest {

    @Test
    void crossingSoftLimitNeverStopsTheTask() {
        ExecutionControl control = new ExecutionControl(60_000, 100, 1_000);
        control.start("soft-limit");

        assertEquals(ExecutionControl.StopReason.NONE,
                control.afterModelTokens("soft-limit", 600), "600 < 1000，未越线");
        assertEquals(ExecutionControl.StopReason.NONE,
                control.afterModelTokens("soft-limit", 600),
                "600+600 > 1000 越过告警线，但必须仍然返回 NONE —— 这是本次改造的核心断言");
        assertEquals(1_200, control.estimatedTokens("soft-limit"), "累计值照加不误");
        assertTrue(control.tokenSoftLimitExceeded("soft-limit"));
    }

    @Test
    void warningIsEmittedOnlyOnce() {
        ExecutionControl control = new ExecutionControl(60_000, 100, 1_000);
        control.start("warn-once");
        control.afterModelTokens("warn-once", 600);
        assertFalse(control.markTokenSoftLimitWarned("warn-once"), "未越线时不该告警");

        control.afterModelTokens("warn-once", 600);
        assertTrue(control.markTokenSoftLimitWarned("warn-once"), "首次越线告警");
        assertFalse(control.markTokenSoftLimitWarned("warn-once"),
                "同一任务内第二次不得重复告警，否则每轮刷日志");
        assertTrue(control.tokenSoftLimitExceeded("warn-once"), "告警去重不影响越线判定本身");
    }

    @Test
    void newTaskResetsAccumulationAndWarnFlag() {
        ExecutionControl control = new ExecutionControl(60_000, 100, 1_000);
        control.start("task-a");
        control.afterModelTokens("task-a", 900);
        control.afterModelTokens("task-a", 900);
        assertTrue(control.markTokenSoftLimitWarned("task-a"));

        control.start("task-a");
        assertEquals(0, control.estimatedTokens("task-a"), "新任务必须从 0 开始累计");
        assertFalse(control.tokenSoftLimitExceeded("task-a"), "新任务的告警线重新判定");
        assertFalse(control.markTokenSoftLimitWarned("task-a"), "告警去重标记也要重置");
    }

    @Test
    void inputSnapshotIsOverwrittenNotAccumulated() {
        ExecutionControl control = new ExecutionControl(60_000, 100, 1_000);
        control.start("snapshot");

        control.recordInputTokens("snapshot", 40_000);
        assertEquals(40_000, control.lastInputTokens("snapshot"));
        control.recordInputTokens("snapshot", 55_000);
        assertEquals(55_000, control.lastInputTokens("snapshot"),
                "快照必须覆盖：压缩判据要的是「这次请求多大」，不是历史求和");

        control.recordInputTokens("snapshot", 0);
        assertEquals(55_000, control.lastInputTokens("snapshot"),
                "0 表示 provider 没返 usage，应保留上一次的真实值而不是抹成 0");
    }

    /**
     * 子代理通过 {@code attach} 共享父会话的 lease。快照与告警去重也必须走父账：
     * 若每个子代理各自记一份，父子会看到不同的「当前上下文大小」，
     * 告警也会被重复发出 N 次。
     */
    @Test
    void subagentSharesParentLeaseForSnapshotAndWarning() {
        ExecutionControl control = new ExecutionControl(60_000, 100, 1_000);
        control.start("parent");
        control.attach("parent:sub:1", "parent");

        control.recordInputTokens("parent:sub:1", 33_000);
        assertEquals(33_000, control.lastInputTokens("parent"),
                "子代理写入的快照，父会话必须看得到");

        control.afterModelTokens("parent", 600);
        control.afterModelTokens("parent", 600);
        assertTrue(control.markTokenSoftLimitWarned("parent:sub:1"),
                "子代理也能触发告警");
        assertFalse(control.markTokenSoftLimitWarned("parent"),
                "父会话不得重复告警 —— 去重标记必须在共享 lease 上");
    }
}
