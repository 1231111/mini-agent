package com.miniagent.agent.permission;

import com.miniagent.common.permission.ExecPolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PermissionPolicyBrowserSessionTest {
    @Test
    void clearingAuthenticationIsAnExplicitWriteAndIsNeverPlanSafe() {
        String tool = "browser_clear_session";
        assertFalse(PermissionPolicy.isPlanSafe(tool));
        assertFalse(PermissionPolicy.allowInSpecs(PermissionMode.READ_ONLY, true, tool));
        assertFalse(PermissionPolicy.allowInSpecs(PermissionMode.PLAN, false, tool));
        assertTrue(PermissionPolicy.isAskDangerous(tool));
        assertTrue(PermissionPolicy.needsSessionGrant(PermissionMode.ASK, tool, ExecPolicy.ALLOW, ConfirmPolicy.DANGEROUS));
        assertTrue(PermissionPolicy.needsSessionGrant(PermissionMode.READ_ONLY, tool, ExecPolicy.ALLOW, ConfirmPolicy.AUTO));
        assertFalse(PermissionPolicy.needsSessionGrant(PermissionMode.ACCEPT_EDITS, tool, ExecPolicy.ASK, ConfirmPolicy.AUTO));
        assertTrue(PermissionPolicy.isPlanSafe("browser_close"));
        assertFalse(PermissionPolicy.isAskDangerous("browser_close"));
    }
}
