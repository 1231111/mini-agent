package com.miniagent.agent.core;

import com.miniagent.agent.execution.InMemoryActionJournal;
import com.miniagent.agent.execution.ToolExecutionGuards;
import com.miniagent.agent.execution.ToolPipeline;
import com.miniagent.agent.execution.ToolInvocation;
import com.miniagent.agent.hook.ToolHookChain;
import com.miniagent.agent.permission.SessionPermissionStore;
import com.miniagent.agent.tool.ToolErrorCode;
import com.miniagent.agent.tool.ToolRegistry;
import com.miniagent.agent.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NodeExecutorBoundTest {

    @Test
    void boundRequiresFence() {
        NodeExecutor ex = new NodeExecutor(null, pipeline(new ToolRegistry()));
        assertThrows(IllegalStateException.class,
                () -> ex.executeBound("write_file", "{}"));
        assertThrows(IllegalStateException.class,
                () -> ex.executeBoundInvocation("write_file", "{}"));
    }

    @ParameterizedTest
    @EnumSource(value = ExecutionControl.StopReason.class, mode = EnumSource.Mode.EXCLUDE, names = "NONE")
    void boundInvocationPreservesControlReasonWhileTheOldResultApiStaysCompatible(ExecutionControl.StopReason reason) {
        ToolPipeline pipeline = mock(ToolPipeline.class);
        ToolResult result = ToolResult.unknown("remote write outcome unconfirmed", null);
        ToolInvocation invocation = ToolInvocation.controlStop("write_file", result, reason);
        when(pipeline.invoke(any())).thenReturn(invocation);
        when(pipeline.timeoutSeconds(anyString(), anyString())).thenReturn(60L);
        NodeExecutor executor = new NodeExecutor(null, pipeline);
        ExecutionTurnContext.open("s", 1L, List.of(), () -> true);
        try {
            ToolInvocation actual = executor.executeBoundInvocation("write_file", "{}");
            assertSame(invocation, actual);
            assertEquals(reason, actual.controlStopReason());
            assertSame(result, executor.executeBound("write_file", "{}"));
            verify(pipeline, times(2)).invoke(argThat(request -> request.waitForApproval()));
        } finally {
            ExecutionTurnContext.clear();
        }
    }

    @Test
    void controlSignalsAreStrictAndDoNotInferAnExecutionStopFromOrdinaryToolErrors() {
        ToolResult timeout = ToolResult.failure(ToolErrorCode.TIMEOUT, "tool timeout", false);
        assertEquals(ExecutionControl.StopReason.NONE, ToolInvocation.executed("read_file", timeout).controlStopReason());
        assertEquals(ExecutionControl.StopReason.NONE, new ToolInvocation(
                ToolInvocation.Outcome.EXECUTED, "read_file", timeout, "CANCELLED").controlStopReason());
        assertEquals(ExecutionControl.StopReason.NONE, new ToolInvocation(
                ToolInvocation.Outcome.CONTROL_STOP, "read_file", timeout, "not-a-stop-reason").controlStopReason());
        assertEquals(ExecutionControl.StopReason.NONE, ToolInvocation.controlStop("read_file", timeout).controlStopReason());
        assertThrows(IllegalArgumentException.class, () -> ToolInvocation.controlStop(
                "read_file", timeout, ExecutionControl.StopReason.NONE));
    }

    @Test
    void boundInvocationPreservesTheRealPipelineToolQuotaStopBeforeDispatch() {
        ExecutionControl control = new ExecutionControl(60_000, 1, 10_000);
        control.start("s");
        assertEquals(ExecutionControl.StopReason.NONE, control.beforeTool("s"));
        ToolRegistry registry = new ToolRegistry();
        AtomicInteger calls = new AtomicInteger();
        registry.register("read_file", "read", Map.of(), args -> {
            calls.incrementAndGet();
            return "should not execute";
        });
        NodeExecutor executor = new NodeExecutor(null, pipeline(registry, control));
        ExecutionTurnContext.open("s", 1L, List.of(), () -> true);
        AgentLoop.setCurrentSession("s");
        try {
            ToolInvocation invocation = executor.executeBoundInvocation("read_file", "{}");
            assertEquals(ToolInvocation.Outcome.CONTROL_STOP, invocation.outcome());
            assertEquals(ExecutionControl.StopReason.TOOL_BUDGET_EXCEEDED, invocation.controlStopReason());
            assertEquals(ToolErrorCode.RATE_LIMITED, invocation.result().errorCode());
            assertEquals(0, calls.get());
        } finally {
            ExecutionTurnContext.clear();
            AgentLoop.clearCurrentSession();
        }
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
        return pipeline(registry, new ExecutionControl(60_000, 20, 10_000));
    }

    private static ToolPipeline pipeline(ToolRegistry registry, ExecutionControl control) {
        SessionPermissionStore store = new SessionPermissionStore();
        return new ToolPipeline(
                control,
                new ToolHookChain(List.of()),
                store,
                new InMemoryActionJournal(),
                new ToolExecutionGuards(registry, new com.miniagent.agent.execution.AgentToolsProperties(4)),
                registry,
                new com.miniagent.agent.permission.ExecPolicyService("allow", "", store));
    }
}
