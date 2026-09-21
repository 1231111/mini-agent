package com.miniagent.agent.core;

import com.miniagent.agent.delegate.RoleContext;
import com.miniagent.agent.delegate.SubagentContext;
import com.miniagent.agent.permission.PermissionContext;
import com.miniagent.agent.permission.PermissionMode;
import com.miniagent.agent.todo.TaskTodoContext;
import com.miniagent.agent.tool.BuiltinTools;
import com.miniagent.agent.tool.WorkspaceContext;
import com.miniagent.memory.MemoryStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunScopeTest {

    @AfterEach
    void tearDown() {
        AgentLoop.clearCurrentSession();
        AgentLoop.clearCurrentModels();
        PermissionContext.clear();
        LoopTurnContext.clear();
        ExecutionTurnContext.clear();
        SubagentContext.exit();
        RoleContext.clear();
        TaskTodoContext.clear();
        BuiltinTools.clearCurrentTaskName();
        WorkspaceContext.clearTaskOverride();
        WorkspaceContext.clearRootOverride();
        MemoryStore.clearCurrentUser();
        MemoryStore.clearCurrentTenant();
    }

    @Test
    void bindRestoresPreviousSnapshot() {
        AgentLoop.setCurrentSession("outer");
        PermissionContext.setSession("outer");
        RunScope inner = RunScope.capture().withSession("inner");
        try (var ignored = inner.bind()) {
            assertEquals("inner", AgentLoop.getCurrentSession());
            assertEquals("inner", TaskTodoContext.currentSessionId());
        }
        assertEquals("outer", AgentLoop.getCurrentSession());
        assertEquals("outer", PermissionContext.sessionId());
    }

    @Test
    void nestedBindRestoresExactPrevious() {
        AgentLoop.setCurrentSession("a");
        RunScope scopeA = RunScope.capture();
        AgentLoop.setCurrentSession("b");
        RunScope scopeB = RunScope.capture();
        try (var ignoredA = scopeA.bind()) {
            assertEquals("a", AgentLoop.getCurrentSession());
            try (var ignoredB = scopeB.bind()) {
                assertEquals("b", AgentLoop.getCurrentSession());
            }
            assertEquals("a", AgentLoop.getCurrentSession());
        }
        assertEquals("b", AgentLoop.getCurrentSession());
    }

    @Test
    void asSubagentIsolatesGateAndFenceKeepsTaskName() {
        AgentLoop.setCurrentSession("parent");
        BuiltinTools.restoreTaskName("main_task");
        LoopTurnContext.set(new ClosedGate());
        ExecutionTurnContext.open("parent", 1L, List.of(), () -> true);
        PermissionContext.setSession("parent");
        RunScope parent = RunScope.capture();
        assertEquals("main_task", parent.taskName());
        assertTrue(parent.turnPolicy().hardGate());

        RunScope child = parent.asSubagent(
                "child", "coder", PermissionMode.ASK, Path.of("tmp-sub"));
        assertTrue(child.subagent());
        assertEquals("parent", child.parentSessionId());
        assertEquals("coder", child.role());
        assertEquals("main_task", child.taskName());
        assertEquals("out", child.writeTask());
        assertSame(LoopTurnPolicy.NONE, child.turnPolicy());
        assertNull(child.execution());
        assertTrue(child.permissionForced());
        assertEquals(PermissionMode.ASK, child.permissionMode());

        try (var ignored = child.bind()) {
            assertEquals("child", AgentLoop.getCurrentSession());
            assertTrue(SubagentContext.isActive());
            assertEquals("parent", SubagentContext.parentSessionId());
            assertEquals(PermissionMode.ASK, PermissionContext.mode());
            assertTrue(PermissionContext.planApproved());
            assertEquals("coder", RoleContext.getRole());
            assertEquals("main_task", BuiltinTools.currentTaskName());
            assertEquals("out", WorkspaceContext.getTaskOverride());
            assertNull(ExecutionTurnContext.current());
            assertFalse(LoopTurnContext.current().hardGate());
        }
        assertEquals("parent", AgentLoop.getCurrentSession());
        assertFalse(SubagentContext.isActive());
        assertEquals("main_task", BuiltinTools.currentTaskName());
        assertNull(WorkspaceContext.getTaskOverride());
        assertTrue(LoopTurnContext.current().hardGate());
    }

    /** 只用来证明子代理快照会丢掉父闸门。 */
    private static final class ClosedGate implements LoopTurnPolicy {
        @Override
        public List<String> allowedTools() {
            return List.of("todo");
        }

        @Override
        public boolean forceToolsOnly() {
            return true;
        }

        @Override
        public boolean hardGate() {
            return true;
        }

        @Override
        public String denyTool(String toolName) {
            return "{\"error\":\"gated\"}";
        }

        @Override
        public String denyTodoArgs(String argumentsJson) {
            return null;
        }

        @Override
        public String focusLabel() {
            return "gate";
        }

        @Override
        public Set<Integer> focusTodoIds() {
            return Set.of();
        }

        @Override
        public void markDrift() {
        }

        @Override
        public int driftHits() {
            return 0;
        }

        @Override
        public boolean consumeDrift() {
            return false;
        }
    }
}
