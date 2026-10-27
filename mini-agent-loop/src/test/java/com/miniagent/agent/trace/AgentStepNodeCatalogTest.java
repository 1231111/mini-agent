package com.miniagent.agent.trace;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 轨迹节点登记表的最小约束。
 *
 * <p>背景：{@code ToolPipeline} 曾经用字符串字面量发 {@code PERM_AUTO} / {@code PERM_GRANT}，
 * 而 {@link AgentStepNode} 里只有 {@code PERM_DENY}。后果不是崩溃，是静默降级 ——
 * 日志刷「未知 AgentStepNode code」WARN，节点被当成非核心/非登记处理，轨迹页看不到
 * 「这次是用户批准的还是全自动吞掉的」。现在节点码改成枚举参数，漏登记会编译失败；
 * 本测试守住这两个码必须存在，以及全自动放行的落库/折叠语义不被改回去。</p>
 */
class AgentStepNodeCatalogTest {

    @Test
    void permissionNodesAreRegistered() {
        assertTrue(AgentStepNode.ofCode("PERM_AUTO").isPresent(),
                "PERM_AUTO 必须登记，否则全自动放行在轨迹里不可见");
        assertTrue(AgentStepNode.ofCode("PERM_GRANT").isPresent(),
                "PERM_GRANT 必须登记，否则「用户批准后续跑」与「直接执行」无法区分");
        assertTrue(AgentStepNode.ofCode("PERM_DENY").isPresent());
    }

    @Test
    void fullAutoGrantIsPersistedButNotCore() {
        AgentStepNode auto = AgentStepNode.PERM_AUTO;
        // persisted=true：审计要能事后区分「本来就免授权」与「被全自动放行」。
        assertTrue(auto.isPersisted(), "全自动放行必须落库，否则事后无法审计");
        // core=false：每个危险工具都会记一条，默认折叠，避免把时间线刷满。
        assertFalse(auto.isCore(), "全自动放行不应算核心业务步骤");

        assertTrue(AgentStepNode.PERM_GRANT.isCore(), "用户批准是核心业务事件");
    }
}
