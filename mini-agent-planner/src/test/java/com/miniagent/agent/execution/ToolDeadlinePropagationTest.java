package com.miniagent.agent.execution;

import com.miniagent.agent.core.ExecutionControl;
import com.miniagent.agent.hook.ToolHookChain;
import com.miniagent.agent.permission.ExecPolicyService;
import com.miniagent.agent.permission.SessionPermissionStore;
import com.miniagent.agent.tool.ToolExecutionDeadline;
import com.miniagent.agent.tool.ToolRegistry;
import com.miniagent.agent.tool.ToolStatus;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class ToolDeadlinePropagationTest {
    @Test void realPipelineExposesActualInvocationBudgetToHandler() {
        ToolRegistry registry = new ToolRegistry();
        AtomicLong remaining = new AtomicLong();
        registry.register("browser_snapshot", "snapshot fixture", Map.of(), ignored -> {
            assertNotNull(ToolExecutionDeadline.current());
            remaining.set(ToolExecutionDeadline.current().timeoutMillis(0));
            return "{\"success\":true}";
        });
        ExecutionControl control = new ExecutionControl(60_000, 20, 10_000);
        SessionPermissionStore permissions = new SessionPermissionStore();
        ToolExecutionGuards guards = new ToolExecutionGuards(registry, new AgentToolsProperties(2));
        ToolPipeline pipeline = new ToolPipeline(control, new ToolHookChain(List.of()), permissions,
                new InMemoryActionJournal(), guards, registry, new ExecPolicyService("allow", "", permissions));
        ToolInvocation result = pipeline.invoke(ToolRequest.of("deadline", "browser_snapshot", "{}", 0, "one")
                .withExecutionBudget(2));
        assertEquals(ToolStatus.SUCCESS, result.result().status());
        assertTrue(remaining.get() > 0 && remaining.get() <= 2_000);
        assertNull(ToolExecutionDeadline.current());
    }

    @Test void directGuardedHandlerBindsAndCleansItsDeadlineEvenOnFailure() {
        ToolRegistry registry = new ToolRegistry();
        ToolExecutionGuards guards = new ToolExecutionGuards(registry, new AgentToolsProperties(2));
        ToolInvocationBudget budget = new ToolInvocationBudget(2);
        assertThrows(IllegalStateException.class, () -> guards.executeGuarded("browser_snapshot", "{}", "s", budget, () -> {
            assertSame(budget.deadline(), ToolExecutionDeadline.current());
            throw new IllegalStateException("fixture");
        }));
        assertNull(ToolExecutionDeadline.current());
        assertFalse(guards.hasActiveHandlers("s"));
    }
}
