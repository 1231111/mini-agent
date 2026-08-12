package com.miniagent.agent.todo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TaskTodoStoreSuspendTest {

    @TempDir
    Path dir;

    @Test
    void suspendAndResumeAcrossFreshStoreInstance() {
        String session = "s1";
        TaskTodoStore a = new TaskTodoStore(dir.toString());
        a.set(session, List.of(Map.of(
                "id", 1,
                "content", "draw lifecycle",
                "status", "pending",
                "done_when", "file_exists:x.png")));
        assertTrue(a.hasIncomplete(session));
        assertTrue(a.suspendActive(session));
        assertFalse(a.hasPlan(session));
        assertTrue(a.hasSuspended(session));

        // 模拟另一实例：新 Store 读同一共享目录
        TaskTodoStore b = new TaskTodoStore(dir.toString());
        assertTrue(b.hasSuspended(session));
        assertTrue(b.resumeSuspended(session));
        assertTrue(b.hasIncomplete(session));
        assertFalse(b.hasSuspended(session));
    }
}
