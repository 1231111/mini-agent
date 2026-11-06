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
                PermissionMode.DEFAULT, "exec_command", ExecPolicy.ASK, ConfirmPolicy.DANGEROUS));
        assertTrue(PermissionPolicy.needsSessionGrant(
                PermissionMode.ASK, "exec_command", ExecPolicy.ASK, ConfirmPolicy.DANGEROUS));
        assertFalse(PermissionPolicy.needsSessionGrant(
                PermissionMode.DEFAULT, "exec_command", ExecPolicy.ALLOW, ConfirmPolicy.DANGEROUS));
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
    }

    @Test
    void acceptEditsMustNotPromoteExecPolicyToAllow() {
        // 回归锁：此前 effectiveExecPolicy 在 ACCEPT_EDITS 下把任何非 block 档提升成 ALLOW，
        // 于是"为了不弹编辑确认"切一个模式，就顺带得到了"任意命令免批执行"。
        // 这与 blockIsHardGateThatAcceptEditsCannotBypass 声称的语义自相矛盾，
        // 也让 prod 默认的 ask 档形同虚设。现在 exec 策略原样返回，模式不参与提升。
        assertEquals(ExecPolicy.ASK,
                PermissionPolicy.effectiveExecPolicy(PermissionMode.ACCEPT_EDITS, ExecPolicy.ASK));
        assertEquals(ExecPolicy.ALLOW,
                PermissionPolicy.effectiveExecPolicy(PermissionMode.ACCEPT_EDITS, ExecPolicy.ALLOW));
        // 免批的来源只有两个：用户显式选的 allow 档，或「自动编辑 + 自动确认」的组合。
        // 单独打开自动编辑不构成后者 —— 那正是这里要锁住的行为。
        assertTrue(PermissionPolicy.needsSessionGrant(
                PermissionMode.ACCEPT_EDITS, "exec_command", ExecPolicy.ASK, ConfirmPolicy.DANGEROUS));
        assertFalse(PermissionPolicy.needsSessionGrant(
                PermissionMode.ACCEPT_EDITS, "exec_command", ExecPolicy.ALLOW, ConfirmPolicy.DANGEROUS));
        // 编辑类工具仍按模式的既有语义走"别问我"
        assertFalse(PermissionPolicy.needsSessionGrant(
                PermissionMode.ACCEPT_EDITS, "edit_file", ExecPolicy.ASK, ConfirmPolicy.DANGEROUS));
        assertFalse(PermissionPolicy.needsSessionGrant(
                PermissionMode.ACCEPT_EDITS, "http_post", ExecPolicy.ASK, ConfirmPolicy.DANGEROUS));
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
                PermissionMode.DEFAULT, "exec_command", ExecPolicy.BLOCK, ConfirmPolicy.DANGEROUS));
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
        // 两个都没给 → 安全兜底为 ASK，不能因为配置/profile 漏配而免批执行命令
        assertEquals(ExecPolicy.ASK, ExecPolicyService.resolveGlobal("", ""));
        // 写错必须抛，不能静默降级
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> ExecPolicyService.resolveGlobal("blocc", ""));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> ExecPolicyService.resolveGlobal("", "yes"));
    }

    @Test
    void missingGlobalExecConfigurationFailsSafeToAsk() {
        // 配置文件、profile 或外部导入缺失时都必须落到需批准，不能隐式放行。
        assertEquals(ExecPolicy.ASK, ExecPolicyService.resolveGlobal(null, null));
        assertEquals(ExecPolicy.ASK, ExecPolicyService.resolveGlobal("  ", "  "));
    }

    // ==================== 其余工具不受影响 ====================

    @Test
    void httpPostAlwaysAsksInDefault() {
        assertTrue(PermissionPolicy.needsSessionGrant(
                PermissionMode.DEFAULT, "http_post", ExecPolicy.ALLOW, ConfirmPolicy.DANGEROUS));
    }

    @Test
    void otherDangerousToolsUnaffectedByExecPolicy() {
        // exec 策略不该波及别的工具：ALLOW 也不能让 write_file 在 ASK 模式下免批
        assertTrue(PermissionPolicy.needsSessionGrant(
                PermissionMode.ASK, "write_file", ExecPolicy.ALLOW, ConfirmPolicy.DANGEROUS));
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

    // ==================== READ_ONLY 只读档（对应 WorkBuddy 的 Ask） ====================

    @Test
    void readOnlyModeHidesWriteAndExecFromSpecsEvenWhenApproved() {
        // 只读模式不认「计划已批准」——写/执行工具永远进不了 specs。
        assertFalse(PermissionPolicy.allowInSpecs(
                PermissionMode.READ_ONLY, true, "write_file"));
        assertFalse(PermissionPolicy.allowInSpecs(
                PermissionMode.READ_ONLY, true, "edit_file"));
        assertFalse(PermissionPolicy.allowInSpecs(
                PermissionMode.READ_ONLY, true, "exec_command"));
        assertFalse(PermissionPolicy.allowInSpecs(
                PermissionMode.READ_ONLY, true, "http_post"));
    }

    @Test
    void readOnlyModeStillAllowsReadOnlyWhitelist() {
        // 只读白名单（PLAN_SAFE_TOOLS）在只读模式下仍然可用。
        assertTrue(PermissionPolicy.allowInSpecs(
                PermissionMode.READ_ONLY, false, "read_file"));
        assertTrue(PermissionPolicy.allowInSpecs(
                PermissionMode.READ_ONLY, false, "list_files"));
        assertTrue(PermissionPolicy.allowInSpecs(
                PermissionMode.READ_ONLY, false, "web_search"));
        assertTrue(PermissionPolicy.allowInSpecs(
                PermissionMode.READ_ONLY, false, "todo"));
    }

    @Test
    void readOnlyModeRequiresGrantForLeakedDangerousTools() {
        // 防御性兜底：万一写/执行工具绕过 specs 直接到了执行侧，仍须授权（等于拒绝）。
        assertTrue(PermissionPolicy.needsSessionGrant(
                PermissionMode.READ_ONLY, "write_file", ExecPolicy.ALLOW, ConfirmPolicy.DANGEROUS));
        assertTrue(PermissionPolicy.needsSessionGrant(
                PermissionMode.READ_ONLY, "exec_command", ExecPolicy.ALLOW, ConfirmPolicy.DANGEROUS));
    }

    // ==================== 全自动：自动编辑 + 自动确认 ====================

    @Test
    void fullAutoNeedsBothSwitches() {
        // 产品规则：两个开关同时打开才是全自动。单开任何一个都不改变原有语义。
        assertTrue(PermissionPolicy.isFullAuto(
                PermissionMode.ACCEPT_EDITS, ConfirmPolicy.AUTO));
        assertFalse(PermissionPolicy.isFullAuto(
                PermissionMode.ACCEPT_EDITS, ConfirmPolicy.DANGEROUS));
        assertFalse(PermissionPolicy.isFullAuto(
                PermissionMode.DEFAULT, ConfirmPolicy.AUTO));
        assertFalse(PermissionPolicy.isFullAuto(
                PermissionMode.ASK, ConfirmPolicy.AUTO));
        assertFalse(PermissionPolicy.isFullAuto(
                PermissionMode.PLAN, ConfirmPolicy.AUTO));
        assertFalse(PermissionPolicy.isFullAuto(
                PermissionMode.READ_ONLY, ConfirmPolicy.AUTO),
                "只读是硬约束，不能被确认策略降级");
        assertFalse(PermissionPolicy.isFullAuto(PermissionMode.ACCEPT_EDITS, null));
    }

    @Test
    void fullAutoRemovesEveryApprovalEntryPoint() {
        // 「后续步骤不再询问授权」必须覆盖所有会弹批准的工具，而不是只放行 exec_command。
        for (String tool : java.util.List.of(
                "exec_command", "http_post", "write_file", "edit_file",
                "browser_click", "browser_evaluate", "mcp__anything")) {
            assertFalse(PermissionPolicy.needsSessionGrant(
                            PermissionMode.ACCEPT_EDITS, tool, ExecPolicy.ASK, ConfirmPolicy.AUTO),
                    "全自动下不应再产生批准入口: " + tool);
        }
    }

    @Test
    void fullAutoDoesNotTurnBlockIntoExecution() {
        // 「不询问」不等于「允许执行」：block 仍由前置闸门直接拒绝。
        assertTrue(PermissionPolicy.isExecBlocked("exec_command", ExecPolicy.BLOCK));
        assertFalse(PermissionPolicy.needsSessionGrant(
                PermissionMode.ACCEPT_EDITS, "exec_command", ExecPolicy.BLOCK, ConfirmPolicy.AUTO));
    }

    @Test
    void autoConfirmAloneStillAsksForExec() {
        // 只开「自动确认」只影响待办确认，不放开命令。
        assertTrue(PermissionPolicy.needsSessionGrant(
                PermissionMode.DEFAULT, "exec_command", ExecPolicy.ASK, ConfirmPolicy.AUTO));
        assertTrue(PermissionPolicy.needsSessionGrant(
                PermissionMode.ASK, "exec_command", ExecPolicy.ASK, ConfirmPolicy.AUTO));
    }
}
