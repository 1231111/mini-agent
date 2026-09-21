package com.miniagent.agent.execution;

import com.miniagent.agent.core.ExecutionControl;
import com.miniagent.agent.core.ExecutionTurnContext;
import com.miniagent.agent.core.LoopTurnContext;
import com.miniagent.agent.core.LoopTurnPolicy;
import com.miniagent.agent.core.RunScope;
import com.miniagent.agent.hook.ToolHookChain;
import com.miniagent.agent.permission.PermissionContext;
import com.miniagent.agent.permission.PermissionMode;
import com.miniagent.agent.permission.SessionPermissionStore;
import com.miniagent.agent.tool.ToolErrorCode;
import com.miniagent.agent.tool.ToolRegistry;
import com.miniagent.agent.tool.ToolResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolPipelineTest {

    @AfterEach
    void tearDown() {
        LoopTurnContext.clear();
        ExecutionTurnContext.clear();
        PermissionContext.clear();
    }

    @Test
    void invokeRunsRegisteredToolThroughJournal() {
        ToolRegistry registry = new ToolRegistry();
        AtomicInteger calls = new AtomicInteger();
        registry.register("write_file", "w", Map.of(), x -> {
            calls.incrementAndGet();
            return "{\"ok\":true}";
        });
        ToolPipeline pipeline = pipeline(registry);

        ToolInvocation first = pipeline.invoke(ToolRequest.of("s", "write_file", "{}", 0, "r1"));
        assertEquals(ToolInvocation.Outcome.EXECUTED, first.outcome());
        assertTrue(first.result().isSuccess());
        assertEquals(1, calls.get());

        ToolInvocation again = pipeline.invoke(ToolRequest.of("s", "write_file", "{}", 0, "r1"));
        assertTrue(again.result().isSuccess());
        assertTrue(again.text().contains("deduplicated"));
        assertEquals(1, calls.get());
    }

    @Test
    void hardGateDoesNotExecuteTool() {
        ToolRegistry registry = new ToolRegistry();
        AtomicInteger calls = new AtomicInteger();
        registry.register("exec_command", "e", Map.of(), x -> {
            calls.incrementAndGet();
            return "{\"ok\":true}";
        });
        LoopTurnContext.set(new HardGate(List.of("write_file")));
        ToolInvocation inv = pipeline(registry).invoke(
                ToolRequest.of("s", "exec_command", "{}", 0, "r1"));
        assertEquals(ToolInvocation.Outcome.GATE_DENIED, inv.outcome());
        assertEquals(0, calls.get());
    }

    @Test
    void hardGateFollowsCapturedScopeAfterThreadLocalCleared() {
        ToolRegistry registry = new ToolRegistry();
        AtomicInteger calls = new AtomicInteger();
        registry.register("exec_command", "e", Map.of(), x -> {
            calls.incrementAndGet();
            return "{\"ok\":true}";
        });
        LoopTurnContext.set(new HardGate(List.of("write_file")));
        RunScope scope = RunScope.capture().withSession("s");
        LoopTurnContext.clear();
        ToolInvocation inv = pipeline(registry).invoke(
                ToolRequest.of(scope, "exec_command", "{}", 0, "r1"));
        assertEquals(ToolInvocation.Outcome.GATE_DENIED, inv.outcome());
        assertEquals(0, calls.get());
    }

    @Test
    void planDenyFollowsCapturedScopeAfterThreadLocalCleared() {
        ToolRegistry registry = new ToolRegistry();
        AtomicInteger calls = new AtomicInteger();
        registry.register("write_file", "w", Map.of(), x -> {
            calls.incrementAndGet();
            return "{\"ok\":true}";
        });
        PermissionContext.force("s", PermissionMode.PLAN, false);
        RunScope scope = RunScope.capture().withSession("s");
        PermissionContext.clear();
        ToolInvocation inv = pipeline(registry).invoke(
                ToolRequest.of(scope, "write_file", "{}", 0, "r1"));
        assertEquals(ToolInvocation.Outcome.POLICY_DENIED, inv.outcome());
        assertEquals(0, calls.get());
        assertTrue(inv.text().contains("Plan"));
    }

    @Test
    void boundAndReactShareTheSamePipeline() {
        ToolRegistry registry = new ToolRegistry();
        registry.register("read_file", "r", Map.of(), x -> "{\"ok\":true}");
        ToolPipeline pipeline = pipeline(registry);
        ExecutionTurnContext.open("s", 1L, List.of(), () -> true);
        try {
            ToolResult react = pipeline.invoke(ToolRequest.of("s", "read_file", "{}", 1, "a"))
                    .result();
            ToolResult bound = pipeline.invoke(ToolRequest.bound("s", "read_file", "{}"))
                    .result();
            assertTrue(react.isSuccess());
            assertTrue(bound.isSuccess());
            assertEquals(ToolErrorCode.NONE, bound.errorCode());
        } finally {
            ExecutionTurnContext.clear();
        }
    }

    @Test
    void emptyNameIsPolicyDenied() {
        ToolInvocation inv = pipeline(new ToolRegistry()).invoke(null);
        assertEquals(ToolInvocation.Outcome.POLICY_DENIED, inv.outcome());
        assertFalse(inv.result().isSuccess());
    }

    private static final class HardGate implements LoopTurnPolicy {
        private final List<String> allowed;

        HardGate(List<String> allowed) {
            this.allowed = allowed;
        }

        @Override
        public List<String> allowedTools() {
            return allowed;
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
            if (toolName != null && allowed.contains(toolName)) {
                return null;
            }
            return "{\"error\":\"hard gate deny " + toolName + "\"}";
        }

        @Override
        public String denyTodoArgs(String argumentsJson) {
            return null;
        }

        @Override
        public String focusLabel() {
            return "step";
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

    private static ToolPipeline pipeline(ToolRegistry registry) {
        return new ToolPipeline(
                new ExecutionControl(60_000, 20, 10_000),
                new ToolHookChain(List.of()),
                new SessionPermissionStore(),
                new InMemoryActionJournal(),
                new ToolExecutionGuards(registry, 4),
                registry,
                true);
    }
}
