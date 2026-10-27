package com.miniagent.agent.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TaskExecutionContextTest {
    @AfterEach
    void clear() {
        TaskExecutionContext.clear();
    }

    @Test
    void nestedBindingsRestorePreviousTaskId() {
        TaskExecutionContext.set(3L);
        try (var outer = TaskExecutionContext.bind(8L)) {
            assertEquals(8L, TaskExecutionContext.currentTaskId());
            try (var inner = TaskExecutionContext.bind(13L)) {
                assertEquals(13L, TaskExecutionContext.currentTaskId());
            }
            assertEquals(8L, TaskExecutionContext.currentTaskId());
        }
        assertEquals(3L, TaskExecutionContext.currentTaskId());
    }
}
