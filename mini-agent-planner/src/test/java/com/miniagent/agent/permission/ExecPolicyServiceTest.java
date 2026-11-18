package com.miniagent.agent.permission;

import com.miniagent.common.permission.ExecPolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecPolicyServiceTest {

    @Test
    void globalBlockCannotBeRaisedBySessionOverrides() {
        SessionPermissionStore store = new SessionPermissionStore();
        ExecPolicyService service = new ExecPolicyService("block", "", store);

        store.setExecPolicyOverride("ask-session", ExecPolicy.ASK);
        store.setExecPolicyOverride("allow-session", ExecPolicy.ALLOW);

        assertTrue(service.isBlockLocked());
        assertEquals(ExecPolicy.BLOCK, service.effective("ask-session"));
        assertEquals(ExecPolicy.BLOCK, service.effective("allow-session"));
        assertEquals(ExecPolicy.BLOCK,
                service.effective("allow-session", PermissionMode.ACCEPT_EDITS));
    }

    @Test
    void nonBlockGlobalPolicyStillHonorsSessionOverrides() {
        SessionPermissionStore store = new SessionPermissionStore();
        ExecPolicyService service = new ExecPolicyService("ask", "", store);
        store.setExecPolicyOverride("s1", ExecPolicy.ALLOW);

        assertFalse(service.isBlockLocked());
        assertEquals(ExecPolicy.ALLOW, service.effective("s1"));
        assertEquals(ExecPolicy.ASK, service.effective("other"));
    }
}
