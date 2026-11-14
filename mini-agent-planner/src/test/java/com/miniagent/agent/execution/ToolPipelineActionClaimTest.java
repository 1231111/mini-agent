package com.miniagent.agent.execution;

import com.miniagent.agent.core.ExecutionControl;
import com.miniagent.agent.core.ExecutionTurnContext;
import com.miniagent.agent.hook.ToolHookChain;
import com.miniagent.agent.permission.ExecPolicyService;
import com.miniagent.agent.permission.SessionPermissionStore;
import com.miniagent.agent.tool.ToolRegistry;
import com.miniagent.agent.tool.ToolStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ToolPipelineActionClaimTest {
    private static final ExecutionTurnContext.ActionBinding BINDING = new ExecutionTurnContext.ActionBinding(
            "action", "node", "write_file", "fixed-binding");

    @AfterEach void clearContext() { ExecutionTurnContext.clear(); }

    @Test void aNewLogicalExecutionDoesNotDeduplicateThePreviousTasksSuccess() {
        AtomicInteger calls = new AtomicInteger();
        ToolPipeline pipeline = pipeline(new InMemoryActionJournal(), calls);
        open("first-task", 1);
        assertTrue(pipeline.invoke(request("{}")).result().isSuccess());
        ToolInvocation duplicate = pipeline.invoke(request("{}"));
        assertEquals(ToolStatus.UNKNOWN, duplicate.result().status());
        assertTrue(duplicate.text().contains("deduplicated"));
        open("second-task", 1);
        assertTrue(pipeline.invoke(request("{}")).result().isSuccess());
        assertEquals(2, calls.get());
    }

    @Test void changedArgumentsWithinTheSameBindingHaveDistinctActionReceipts() {
        AtomicInteger calls = new AtomicInteger();
        ToolPipeline pipeline = pipeline(new InMemoryActionJournal(), calls);
        open("one-task", 1);
        assertTrue(pipeline.invoke(request("{\"path\":\"first\"}")).result().isSuccess());
        assertTrue(pipeline.invoke(request("{\"path\":\"second\"}")).result().isSuccess());
        ToolInvocation duplicate = pipeline.invoke(request("{\"path\":\"second\"}"));
        assertEquals(ToolStatus.UNKNOWN, duplicate.result().status());
        assertTrue(duplicate.text().contains("requiresReadback"));
        assertEquals(2, calls.get());
    }

    @Test void unresolvedPreUpgradeReceiptStillBlocksANewFormatClaimUntilPlanRevision() {
        AtomicInteger calls = new AtomicInteger();
        InMemoryActionJournal journal = new InMemoryActionJournal();
        journal.append(new ActionJournalEntry(new ActionJournalKey("session", "1", "node", "fixed-binding"),
                "write_file", "legacy-hash", ActionExecutionStatus.UNKNOWN, 1, 1L, "OUTCOME_UNKNOWN", "", ""));
        ToolPipeline pipeline = pipeline(journal, calls);
        open("task-after-upgrade", 1);
        assertEquals(ToolStatus.UNKNOWN, pipeline.invoke(request("{}")).result().status());
        assertEquals(0, calls.get());
        open("task-after-upgrade", 2);
        assertTrue(pipeline.invoke(request("{}")).result().isSuccess());
        assertEquals(1, calls.get());
    }

    @Test void unsupportedOrFailedClaimNeverDispatchesTheTool() {
        AtomicInteger calls = new AtomicInteger();
        ActionJournal unsupported = new ActionJournal() {
            @Override public void append(ActionJournalEntry entry) { fail("claim must precede any outcome"); }
            @Override public Optional<ActionJournalEntry> latest(ActionJournalKey key) { return Optional.empty(); }
            @Override public List<ActionJournalEntry> unresolved() { return List.of(); }
        };
        ToolInvocation result = pipeline(unsupported, calls).invoke(request("{}"));
        assertFalse(result.result().isSuccess());
        assertEquals(0, calls.get());
    }

    @Test void aFailedOutcomeWriteReturnsUnknownAndTheOriginalPermitStillBlocksReplay() {
        AtomicInteger calls = new AtomicInteger();
        InMemoryActionJournal authority = new InMemoryActionJournal();
        ActionJournal failingOutcomes = new ActionJournal() {
            @Override public Claim claim(ActionJournalKey key, String tool, String hash, boolean retry) {
                return authority.claim(key, tool, hash, retry);
            }
            @Override public Claim claimEffect(ActionJournalKey key, String tool, String hash) {
                return authority.claimEffect(key, tool, hash);
            }
            @Override public void append(ActionJournalEntry entry) { throw new IllegalStateException("fixture database unavailable"); }
            @Override public Optional<ActionJournalEntry> latest(ActionJournalKey key) { return authority.latest(key); }
            @Override public List<ActionJournalEntry> unresolved() { return List.of(); }
        };
        ToolPipeline pipeline = pipeline(failingOutcomes, calls);
        assertEquals(ToolStatus.UNKNOWN, pipeline.invoke(request("{}")).result().status());
        assertEquals(ToolStatus.UNKNOWN, pipeline.invoke(request("{}")).result().status());
        assertEquals(1, calls.get());
    }

    private static void open(String executionId, long revision) {
        ExecutionTurnContext.open("session", executionId, revision, List.of(BINDING), () -> true);
    }

    private static ToolRequest request(String arguments) {
        return ToolRequest.of("session", "write_file", arguments, 1, "same-run").withActionBinding(BINDING);
    }

    private static ToolPipeline pipeline(ActionJournal journal, AtomicInteger calls) {
        ToolRegistry registry = new ToolRegistry();
        registry.register("write_file", "fixture", Map.of(), arguments -> {
            calls.incrementAndGet(); return "{\"success\":true}";
        });
        SessionPermissionStore permissions = new SessionPermissionStore();
        return new ToolPipeline(new ExecutionControl(60000, 20, 10000), new ToolHookChain(List.of()), permissions,
                journal, new ToolExecutionGuards(registry, new AgentToolsProperties(4)), registry,
                new ExecPolicyService("allow", "", permissions));
    }
}
