package com.miniagent.agent.permission;

import com.miniagent.common.permission.ExecPolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PermissionPolicyDesktopTest {
    @Test
    void foregroundActionsNeedApprovalEvenWithAutomaticEditingOrAutomaticConfirmationAlone() {
        for (PermissionMode mode : PermissionMode.values()) {
            assertTrue(PermissionPolicy.needsSessionGrant(mode, "desktop_act", ExecPolicy.ALLOW,
                    ConfirmPolicy.DANGEROUS), "a single mode switch cannot grant arbitrary foreground input: " + mode);
        }
        assertTrue(PermissionPolicy.needsSessionGrant(PermissionMode.DEFAULT, "desktop_act",
                ExecPolicy.ALLOW, ConfirmPolicy.AUTO));
        assertFalse(PermissionPolicy.needsSessionGrant(PermissionMode.ACCEPT_EDITS, "desktop_act",
                ExecPolicy.ASK, ConfirmPolicy.AUTO), "explicit full-auto retains its existing authorization semantics");
    }

    @Test
    void unapprovedPlanAndReadOnlyModeExposeObservationAndCleanupButHideActions() {
        for (PermissionMode mode : new PermissionMode[]{PermissionMode.PLAN, PermissionMode.READ_ONLY}) {
            assertTrue(PermissionPolicy.allowInSpecs(mode, false, "desktop_observe"));
            assertTrue(PermissionPolicy.allowInSpecs(mode, false, "desktop_release"));
            assertFalse(PermissionPolicy.allowInSpecs(mode, false, "desktop_act"));
        }
        assertTrue(PermissionPolicy.allowInSpecs(PermissionMode.PLAN, true, "desktop_act"));
        assertFalse(PermissionPolicy.allowInSpecs(PermissionMode.READ_ONLY, true, "desktop_act"));
    }

    @Test
    void observationAndOwnerCleanupDoNotNeedAdditionalApproval() {
        for (PermissionMode mode : PermissionMode.values()) {
            assertFalse(PermissionPolicy.needsSessionGrant(mode, "desktop_observe", ExecPolicy.ASK,
                    ConfirmPolicy.DANGEROUS));
            assertFalse(PermissionPolicy.needsSessionGrant(mode, "desktop_release", ExecPolicy.ASK,
                    ConfirmPolicy.DANGEROUS));
        }
    }
}
