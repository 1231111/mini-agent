package com.miniagent.agent.core;

import com.miniagent.agent.execution.InMemoryActionJournal;
import com.miniagent.agent.execution.ToolExecutionGuards;
import com.miniagent.agent.execution.ToolPipeline;
import com.miniagent.agent.hook.ToolHookChain;
import com.miniagent.agent.permission.SessionPermissionStore;
import com.miniagent.agent.tool.ToolErrorCode;
import com.miniagent.agent.tool.ToolRegistry;
import com.miniagent.agent.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeExecutorBoundTest {

    @Test
    void boundRequiresFence() {
        NodeExecutor ex = new NodeExecutor(null, pipeline(new ToolRegistry()));
        assertThrows(IllegalStateException.class,
                () -> ex.executeBound("write_file", "{}"));
    }

    @Test
    void boundRunsRegisteredToolThroughPipeline() {
        ToolRegistry registry = new ToolRegistry();
        registry.register("write_file", "w", Map.of(), x -> "{\"ok\":true}");
        NodeExecutor ex = new NodeExecutor(null, pipeline(registry));
        ExecutionTurnContext.open("s", 1L, List.of(), () -> true);
        try {
            ToolResult r = ex.executeBound("write_file", "{}");
            assertTrue(r.isSuccess());
            assertEquals("{\"ok\":true}", r.legacyText());
            assertEquals(ToolErrorCode.NONE, r.errorCode());
        } finally {
            ExecutionTurnContext.clear();
        }
    }

    private static ToolPipeline pipeline(ToolRegistry registry) {
        return new ToolPipeline(
                new ExecutionControl(60_000, 20, 10_000),
                new ToolHookChain(List.of()),
                new SessionPermissionStore(),
                new InMemoryActionJournal(),
                new ToolExecutionGuards(registry, new com.miniagent.agent.execution.AgentToolsProperties(4)),
                registry,
                true);
    }
}
