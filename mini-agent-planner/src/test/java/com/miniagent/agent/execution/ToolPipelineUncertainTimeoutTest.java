package com.miniagent.agent.execution;

import com.miniagent.agent.core.ExecutionControl;
import com.miniagent.agent.hook.ToolHookChain;
import com.miniagent.agent.permission.ExecPolicyService;
import com.miniagent.agent.permission.SessionPermissionStore;
import com.miniagent.agent.tool.*;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ToolPipelineUncertainTimeoutTest {
    @Test
    void cooperativeWriteExceptionAfterDeadlineCannotDowngradeUnknownToRetryableTimeout() {
        Fixture fixture = new Fixture();
        AtomicInteger writes = new AtomicInteger();
        fixture.registry.register(write(args -> {
            writes.incrementAndGet();
            try { Thread.sleep(10_000); }
            catch (InterruptedException interrupted) {
                throw new IllegalStateException("response lost after remote commit", interrupted);
            }
            return "unexpected";
        }));
        ToolRequest request = ToolRequest.of("uncertain-timeout", "write_file", "{}", 0, "one");

        ToolResult result = fixture.pipeline.invoke(request).result();

        assertEquals(ToolStatus.UNKNOWN, result.status());
        assertTrue(result.message().contains("remote commit"), "retain the handler's more precise unresolved result");
        assertFalse(result.retriable());
        assertEquals(ActionExecutionStatus.UNKNOWN, fixture.journal.status());
        assertEquals(ToolStatus.UNKNOWN, fixture.pipeline.invoke(request).result().status());
        assertEquals(1, writes.get());
    }

    @Test
    void successfulWriteAcknowledgementAfterDeadlineDoesNotEnableAutomaticRetry() {
        Fixture fixture = new Fixture();
        AtomicInteger writes = new AtomicInteger();
        fixture.registry.register(write(args -> {
            writes.incrementAndGet();
            try { Thread.sleep(10_000); }
            catch (InterruptedException ignored) { /* The SDK supplies a late successful reply. */ }
            return "{\"success\":true}";
        }));
        ToolRequest request = ToolRequest.of("uncertain-timeout", "write_file", "{}", 0, "one");

        ToolResult result = fixture.pipeline.invoke(request).result();

        assertEquals(ToolStatus.UNKNOWN, result.status());
        assertEquals(ActionExecutionStatus.UNKNOWN, fixture.journal.status());
        assertEquals(ToolStatus.UNKNOWN, fixture.pipeline.invoke(request).result().status());
        assertEquals(1, writes.get());
    }

    @Test
    void readonlyCooperativeExceptionStillUsesOrdinaryTimeoutContract() {
        Fixture fixture = new Fixture();
        fixture.registry.register(Tool.builder().name("read_file").sideEffect(ToolSideEffect.READ_ONLY)
                .executionProfile(profile()).handler(args -> {
                    try { Thread.sleep(10_000); }
                    catch (InterruptedException interrupted) { throw new IllegalStateException(interrupted); }
                    return "unused";
                }).build());

        ToolResult result = fixture.pipeline.invoke(
                ToolRequest.of("uncertain-timeout", "read_file", "{}", 0, "read")).result();

        assertEquals(ToolStatus.TIMEOUT, result.status());
        assertTrue(result.retriable());
        assertEquals(ActionExecutionStatus.TIMEOUT, fixture.journal.status());
    }

    private static Tool write(java.util.function.Function<String, String> handler) {
        return Tool.builder().name("write_file").sideEffect(ToolSideEffect.EXTERNAL_WRITE)
                .executionProfile(profile()).handler(handler).build();
    }

    private static ToolExecutionProfile profile() {
        return new ToolExecutionProfile(1, 0, 2, ToolConcurrencyScope.NONE, "", "",
                ToolTimeoutRecovery.VERIFY, "；先核验实际状态", 0);
    }

    private static class Fixture {
        final ToolRegistry registry = new ToolRegistry();
        final RecordingJournal journal = new RecordingJournal();
        final SessionPermissionStore permissions = new SessionPermissionStore();
        final ToolPipeline pipeline = new ToolPipeline(new ExecutionControl(60_000, 20, 10_000),
                new ToolHookChain(new ArrayList<>()), permissions, journal,
                new ToolExecutionGuards(registry, new AgentToolsProperties(4)), registry,
                new ExecPolicyService("allow", "", permissions));
    }

    private static class RecordingJournal implements ActionJournal {
        final Map<ActionJournalKey, ActionJournalEntry> entries = new ConcurrentHashMap<>();
        public void append(ActionJournalEntry entry) { entries.put(entry.key(), entry); }
        public Optional<ActionJournalEntry> latest(ActionJournalKey key) { return Optional.ofNullable(entries.get(key)); }
        public List<ActionJournalEntry> unresolved() { return List.of(); }
        ActionExecutionStatus status() { return entries.values().iterator().next().status(); }
    }
}
