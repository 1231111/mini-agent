package com.miniagent.config.service;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/** local 模式会话互斥（不启 Redis）。 */
class TaskRunServiceLocalLockTest {

    @Test
    @SuppressWarnings("unchecked")
    void localSessionLockMutualExclusion() throws Exception {
        TaskRunService svc = new TaskRunService();
        Field locks = TaskRunService.class.getDeclaredField("localSessionLocks");
        locks.setAccessible(true);
        ConcurrentHashMap<String, Boolean> map =
                (ConcurrentHashMap<String, Boolean>) locks.get(svc);

        assertNull(map.putIfAbsent("sess-a", Boolean.TRUE));
        assertNotNull(map.putIfAbsent("sess-a", Boolean.TRUE));
        assertTrue(svc.renewSessionLock("sess-a"));
        assertFalse(svc.renewSessionLock("sess-missing"));
        map.remove("sess-a");
        assertFalse(svc.renewSessionLock("sess-a"));
    }
}
