package com.miniagent.agent.permission;

import com.miniagent.common.permission.ExecPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PermissionPolicyBrowserFormTest {
    @Test
    void readOnlyAndUnapprovedPlansCanInspectButCannotModifyFormFields() {
        for (PermissionMode mode : List.of(PermissionMode.READ_ONLY, PermissionMode.PLAN)) {
            for (String name : List.of("browser_form_inspect", "browser_form_verify")) {
                assertTrue(PermissionPolicy.allowInSpecs(mode, false, name), mode + ": " + name);
                assertFalse(PermissionPolicy.needsSessionGrant(mode, name, ExecPolicy.ASK,
                        ConfirmPolicy.DANGEROUS), name);
            }
            for (String name : List.of("browser_select", "browser_set_checked", "browser_upload")) {
                assertFalse(PermissionPolicy.allowInSpecs(mode, false, name), mode + ": " + name);
            }
        }
    }

    @Test
    void askApprovalCoversSelectionCheckboxesAndUploadIncludingAutomaticConfirmationAlone() {
        for (String name : List.of("browser_select", "browser_set_checked", "browser_upload")) {
            assertTrue(PermissionPolicy.isAskDangerous(name), name);
            assertTrue(PermissionPolicy.needsSessionGrant(PermissionMode.ASK, name,
                    ExecPolicy.ALLOW, ConfirmPolicy.DANGEROUS), name);
            assertTrue(PermissionPolicy.needsSessionGrant(PermissionMode.ASK, name,
                    ExecPolicy.ALLOW, ConfirmPolicy.AUTO), name);
            assertFalse(PermissionPolicy.needsSessionGrant(PermissionMode.ACCEPT_EDITS, name,
                    ExecPolicy.ASK, ConfirmPolicy.AUTO), "explicit full-auto retains existing semantics: " + name);
            assertTrue(PermissionPolicy.needsSessionGrant(PermissionMode.READ_ONLY, name,
                    ExecPolicy.ALLOW, ConfirmPolicy.AUTO), name);
        }
    }
}
