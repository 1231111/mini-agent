package com.miniagent.agent.permission;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PermissionPolicyTest {

    @Test
    void execStaysAskWhenGloballyDisabled() {
        assertTrue(PermissionPolicy.needsSessionGrant(
                PermissionMode.DEFAULT, "exec_command", false));
        assertFalse(PermissionPolicy.needsSessionGrant(
                PermissionMode.DEFAULT, "exec_command", true));
        assertTrue(PermissionPolicy.allowInSpecs(
                PermissionMode.DEFAULT, false, "exec_command"));
    }

    @Test
    void httpPostAlwaysAsksInDefault() {
        assertTrue(PermissionPolicy.needsSessionGrant(
                PermissionMode.DEFAULT, "http_post", true));
    }

    @Test
    void execEnabledSkipsAskMode() {
        assertFalse(PermissionPolicy.needsSessionGrant(
                PermissionMode.ASK, "exec_command", true));
        assertTrue(PermissionPolicy.needsSessionGrant(
                PermissionMode.ASK, "exec_command", false));
        assertTrue(PermissionPolicy.needsSessionGrant(
                PermissionMode.ASK, "write_file", true));
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
