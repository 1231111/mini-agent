package com.miniagent.agent.permission;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Plan 批准后，同线程 setSession 路径应立刻读到 Store 新值。 */
class PermissionContextLiveStoreTest {

    @AfterEach
    void tearDown() {
        PermissionContext.clear();
        PermissionContext.bindStore(null);
    }

    @Test
    void planApproveVisibleWithoutRebind() {
        SessionPermissionStore store = new SessionPermissionStore();
        PermissionContext.bindStore(store);
        store.setMode("s1", PermissionMode.PLAN);
        PermissionContext.setSession("s1");
        assertFalse(PermissionContext.planApproved());

        store.approvePlan("s1");
        assertTrue(PermissionContext.planApproved());
        assertEquals(PermissionMode.PLAN, PermissionContext.mode());
    }
}
