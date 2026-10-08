package com.miniagent.agent.permission;

import com.miniagent.common.permission.ExecPolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PermissionPolicyTest {

    // ==================== exec 三档策略 ====================

    @Test
    void execAskNeedsGrantInDefaultMode() {
        // ask = 需会话批准。DEFAULT 与 ASK 模式下都要批；ALLOW 才免批。
        assertTrue(PermissionPolicy.needsSessionGrant(
                PermissionMode.DEFAULT, "exec_command", ExecPolicy.ASK));
        assertTrue(PermissionPolicy.needsSessionGrant(
                PermissionMode.ASK, "exec_command", ExecPolicy.ASK));
        assertFalse(PermissionPolicy.needsSessionGrant(
                PermissionMode.DEFAULT, "exec_command", ExecPolicy.ALLOW));
    }

    @Test
    void blockIsHardGateThatAcceptEditsCannotBypass() {
        // 这是本次设计的核心不变量：
        // "自动编辑（ACCEPT_EDITS）"的语义是"别问我"，不是"我允许执行命令"。
        // 用户显式把 exec 设为 block 之后，ACCEPT_EDITS 不该把它打开。
        assertEquals(ExecPolicy.BLOCK,
                PermissionPolicy.effectiveExecPolicy(PermissionMode.ACCEPT_EDITS, ExecPolicy.BLOCK));
        assertTrue(PermissionPolicy.isExecBlocked("exec_command",
                PermissionPolicy.effectiveExecPolicy(PermissionMode.ACCEPT_EDITS, ExecPolicy.BLOCK)));
        // 而所有非 block 档在 ACCEPT_EDITS 下被提升成 ALLOW（保持该模式"跳过二次询问"的旧语义）
        assertEquals(ExecPolicy.ALLOW,
                PermissionPolicy.effectiveExecPolicy(PermissionMode.ACCEPT_EDITS, ExecPolicy.ASK));
        assertEquals(ExecPolicy.ALLOW,
                PermissionPolicy.effectiveExecPolicy(PermissionMode.ACCEPT_EDITS, ExecPolicy.ALLOW));
    }

    @Test
    void effectivePolicyLeavesOtherModesUntouched() {
        assertEquals(ExecPolicy.ASK,
                PermissionPolicy.effectiveExecPolicy(PermissionMode.DEFAULT, ExecPolicy.ASK));
        assertEquals(ExecPolicy.ASK,
                PermissionPolicy.effectiveExecPolicy(PermissionMode.PLAN, ExecPolicy.ASK));
        // configured=null 兜底成 ASK（最紧的可批准档），不是 ALLOW
        assertEquals(ExecPolicy.ASK,
                PermissionPolicy.effectiveExecPolicy(PermissionMode.DEFAULT, null));
    }

    @Test
    void needsSessionGrantMustNotOfferApprovalForBlockedExec() {
        // block 的语义是"直接拒绝"，不是"弹批准"。needsSessionGrant 返回 false 只是
        // "不产生批准入口"，真正的拒绝由 isExecBlocked 在前置检查里完成。
        // 这条测试锁住这个分工，避免以后有人误以为 false 就是放行。
        assertFalse(PermissionPolicy.needsSessionGrant(
                PermissionMode.DEFAULT, "exec_command", ExecPolicy.BLOCK));
        assertTrue(PermissionPolicy.isExecBlocked("exec_command", ExecPolicy.BLOCK));
    }

    @Test
    void blockOnlyAppliesToExecCommand() {
        assertFalse(PermissionPolicy.isExecBlocked("write_file", ExecPolicy.BLOCK));
        assertFalse(PermissionPolicy.isExecBlocked(null, ExecPolicy.BLOCK));
    }

    @Test
    void execPolicyParsingRejectsUnknownInsteadOfDefaulting() {
        assertEquals(ExecPolicy.BLOCK, ExecPolicy.parse("block"));
        assertEquals(ExecPolicy.BLOCK, ExecPolicy.parse("DENY"));
        assertEquals(ExecPolicy.ASK, ExecPolicy.parse(" Ask "));
        assertEquals(ExecPolicy.ALLOW, ExecPolicy.parse("allow"));
        assertEquals(ExecPolicy.ALLOW, ExecPolicy.parse("auto"));
        // 关键：认不出来必须返回 null（配置写错要启动就炸、API 传错要能回 400），
        // 而不是静默兜底成某一档。
        assertNull(ExecPolicy.parse("yes-please"));
        assertNull(ExecPolicy.parse(""));
        assertNull(ExecPolicy.parse(null));
    }

    @Test
    void legacyExecEnabledMapsFalseToAskNotBlock() {
        // 旧语义：exec-enabled=false 是"默认需会话批准"，不是"禁止执行"。
        // 映射成 BLOCK 会让存量配置的行为悄悄变严 —— 那是不可接受的静默变更。
        assertEquals(ExecPolicy.ALLOW, ExecPolicy.fromLegacyExecEnabled("true"));
        assertEquals(ExecPolicy.ASK, ExecPolicy.fromLegacyExecEnabled("false"));
        assertNull(ExecPolicy.fromLegacyExecEnabled(""));
        assertNull(ExecPolicy.fromLegacyExecEnabled("maybe"));
    }

    @Test
    void globalResolutionPrefersNewKeyAndFailsLoudlyOnTypo() {
        // 新键优先；两个都给时新键赢
        assertEquals(ExecPolicy.BLOCK, ExecPolicyService.resolveGlobal("block", "true"));
        // 新键没给时回退旧键
        assertEquals(ExecPolicy.ASK, ExecPolicyService.resolveGlobal("", "false"));
        assertEquals(ExecPolicy.ALLOW, ExecPolicyService.resolveGlobal(null, "true"));
        // 两个都没给 → 与旧代码 @Value("${agent.tools.exec-enabled:true}") 一致，即 ALLOW
        assertEquals(ExecPolicy.ALLOW, ExecPolicyService.resolveGlobal("", ""));
        // 写错必须抛，不能静默降级
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> ExecPolicyService.resolveGlobal("blocc", ""));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> ExecPolicyService.resolveGlobal("", "yes"));
    }

    // ==================== 其余工具不受影响 ====================

    @Test
    void httpPostAlwaysAsksInDefault() {
        assertTrue(PermissionPolicy.needsSessionGrant(
                PermissionMode.DEFAULT, "http_post", ExecPolicy.ALLOW));
    }

    @Test
    void otherDangerousToolsUnaffectedByExecPolicy() {
        // exec 策略不该波及别的工具：ALLOW 也不能让 write_file 在 ASK 模式下免批
        assertTrue(PermissionPolicy.needsSessionGrant(
                PermissionMode.ASK, "write_file", ExecPolicy.ALLOW));
    }

    @Test
    void planModeHidesExecFromSpecsUntilApproved() {
        assertFalse(PermissionPolicy.allowInSpecs(
                PermissionMode.PLAN, false, "exec_command"));
        assertTrue(PermissionPolicy.allowInSpecs(
                PermissionMode.PLAN, true, "exec_command"));
        assertTrue(PermissionPolicy.allowInSpecs(
                PermissionMode.PLAN, false, "read_file"));
    }
}
