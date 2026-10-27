package com.miniagent.agent.delegate;

import com.miniagent.agent.permission.ConfirmPolicy;
import com.miniagent.agent.permission.PermissionContext;
import com.miniagent.agent.permission.PermissionMode;
import com.miniagent.agent.permission.PermissionPolicy;
import com.miniagent.agent.permission.SessionPermissionStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 子代理进场的权限推导。
 *
 * <p>存在理由：{@code RunScopeTest} 里那条「父全自动 → 子代理也全自动」用的是
 * {@code asSubagent(..., childMode = null, ...)}，等于让子代理直接继承父模式。
 * 生产入口走的是 {@link SubagentScope#enter}，它先算 {@code stricter(parent, DEFAULT)}
 * 再把结果传进去。两条路径不同，于是「用例全绿、线上仍然弹窗」。</p>
 */
class SubagentScopeTest {

    @AfterEach
    void tearDown() {
        PermissionContext.clear();
        // 本类把真实存储装进了静态上下文，用完必须摘掉，避免串到同模块其他用例。
        PermissionContext.bindStore(null);
    }

    /**
     * 父会话全自动时，子代理里也不许重新讨授权。
     *
     * <p>子代理的 {@code permission_ask} 发到的是**子会话**通道，页面订阅的是父会话，
     * 用户根本看不到那个弹窗 —— 表现不是「又弹了一次」，而是子任务一直卡到审批 TTL 结束。
     * 所以这条必须成立。</p>
     */
    @Test
    void fullAutoSurvivesDelegation() {
        bindSession("parent-full-auto", PermissionMode.ACCEPT_EDITS, ConfirmPolicy.AUTO);

        try (SubagentScope ignored = SubagentScope.enter("sub-full-auto", "coder", false)) {
            assertTrue(PermissionPolicy.isFullAuto(
                            PermissionContext.mode(), PermissionContext.confirmPolicy()),
                    "父会话全自动，子代理里也必须全自动，否则子任务会卡在一个收不到的弹窗上");
        }
    }

    /**
     * 只开「自动编辑」时，子代理继续降级为 DEFAULT。
     *
     * <p>与上一条成对：全自动是用户对「这条任务不要打断我」的显式授权；
     * 只开自动编辑不是。子代理仍应拿默认面，避免一个开关顺带把委派也放开。</p>
     */
    @Test
    void singleSwitchStillDowngradesDelegation() {
        bindSession("parent-single", PermissionMode.ACCEPT_EDITS, ConfirmPolicy.DANGEROUS);

        try (SubagentScope ignored = SubagentScope.enter("sub-single", "coder", false)) {
            assertEquals(PermissionMode.DEFAULT, PermissionContext.mode(),
                    "只开自动编辑时，子代理不应拿到 ACCEPT_EDITS");
            assertFalse(PermissionPolicy.isFullAuto(
                    PermissionContext.mode(), PermissionContext.confirmPolicy()));
        }
    }

    /** 父会话更严时仍然只降不升：ASK / PLAN / READ_ONLY 的子代理不能比父宽。 */
    @Test
    void stricterParentIsStillRespected() {
        assertEquals(PermissionMode.ASK,
                SubagentScope.stricter(PermissionMode.ASK, PermissionMode.DEFAULT));
        assertEquals(PermissionMode.PLAN,
                SubagentScope.stricter(PermissionMode.PLAN, PermissionMode.DEFAULT));
        assertEquals(PermissionMode.READ_ONLY,
                SubagentScope.stricter(PermissionMode.READ_ONLY, PermissionMode.DEFAULT));
    }

    // ==================== 测试辅助 ====================

    /**
     * 用真实会话存储写入模式并绑定到 {@link PermissionContext}。
     *
     * <p>必须走存储而不是 {@code force}：这批用例要验的就是「用户设置会不会传到子代理」，
     * 用 force 塞一份快照等于把要验的那一段跳过去。</p>
     */
    private static void bindSession(String sessionId, PermissionMode mode,
                                    ConfirmPolicy confirmPolicy) {
        SessionPermissionStore store = new SessionPermissionStore();
        store.setMode(sessionId, mode);
        store.setConfirmPolicy(sessionId, confirmPolicy);
        PermissionContext.bindStore(store);
        PermissionContext.setSession(sessionId);
    }
}
