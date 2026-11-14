package com.miniagent.agent.execution;

import com.miniagent.agent.core.AgentLoop;
import com.miniagent.agent.core.ExecutionControl;
import com.miniagent.agent.core.ExecutionTurnContext;
import com.miniagent.agent.core.RunScope;
import com.miniagent.agent.hook.ToolHookChain;
import com.miniagent.agent.permission.ConfirmPolicy;
import com.miniagent.agent.permission.ExecPolicyService;
import com.miniagent.agent.permission.PermissionContext;
import com.miniagent.agent.permission.PermissionMode;
import com.miniagent.agent.permission.SessionPermissionStore;
import com.miniagent.agent.tool.ToolRegistry;
import com.miniagent.agent.tool.ToolStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(8)
class ToolPipelineEffectGuardTest {
    private static final String SESSION = "effect-fixture";
    private static final ExecutionTurnContext.ActionBinding ACTION = new ExecutionTurnContext.ActionBinding(
            "random-proposal-action", "browser-node", "browser_click", "stable-browser-action");

    @AfterEach void clear() {
        ExecutionTurnContext.clear();
        PermissionContext.clear();
        AgentLoop.clearCurrentSession();
    }

    @Test void newProviderCallIdAndJsonFormattingCannotReplayAnUnknownWrite() {
        AtomicInteger calls = new AtomicInteger();
        Fixture fixture = fixture("browser_click", args -> {
            calls.incrementAndGet(); throw new IllegalStateException("lost acknowledgement after input");
        });
        open();
        assertEquals(ToolStatus.UNKNOWN, fixture.pipeline.invoke(request("call-one", "browser_click",
                "{\"target\":{\"x\":1,\"y\":2},\"buttons\":[\"left\",\"right\"]}")).result().status());
        // A scheduler retry has a different random action ID but the same logical node.
        ExecutionTurnContext.open(SESSION, "task", 1, List.of(new ExecutionTurnContext.ActionBinding(
                "another-random-action", ACTION.nodeId(), ACTION.toolName(), ACTION.idempotencyKey())), () -> true);
        assertEquals(ToolStatus.UNKNOWN, fixture.pipeline.invoke(request("call-two", "browser_click",
                " { \"buttons\" : [\"left\",\"right\"], \"target\" : {\"y\":2,\"x\":1} } ")).result().status());
        assertEquals(1, calls.get());
    }

    @Test void concurrentDifferentCallsCannotDispatchTheSameRunningEffect() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        Fixture fixture = fixture("browser_click", args -> {
            calls.incrementAndGet(); entered.countDown();
            try { assertTrue(release.await(3, TimeUnit.SECONDS)); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
            return "{\"success\":true}";
        });
        open();
        ToolRequest first = request("first", "browser_click", "{}");
        ToolRequest second = request("second", "browser_click", "{}");
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var running = workers.submit(() -> fixture.pipeline.invoke(first));
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                assertEquals(ToolStatus.UNKNOWN, fixture.pipeline.invoke(second).result().status());
                assertEquals(1, calls.get());
            } finally { release.countDown(); }
            assertTrue(running.get(2, TimeUnit.SECONDS).result().isSuccess());
        }
    }

    @Test void losingAnEffectClaimAfterOwningAnOperationClosesThatOperationAsUnknown() throws Exception {
        InMemoryActionJournal authority = new InMemoryActionJournal();
        List<ActionJournalEntry> outcomes = new CopyOnWriteArrayList<>();
        ActionJournal staleRead = new ActionJournal() {
            public Claim claim(ActionJournalKey key, String tool, String hash, boolean retry) { return authority.claim(key, tool, hash, retry); }
            public Claim claimEffect(ActionJournalKey key, String tool, String hash) { return authority.claimEffect(key, tool, hash); }
            public void append(ActionJournalEntry entry) { authority.append(entry); outcomes.add(entry); }
            // Both callers may have read before the winning effect claim commits.
            public Optional<ActionJournalEntry> latest(ActionJournalKey key) {
                return key.nodeId().startsWith("effect-") ? Optional.empty() : authority.latest(key);
            }
            public List<ActionJournalEntry> unresolved() { return List.of(); }
        };
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        Fixture fixture = fixture(staleRead, "browser_click", args -> {
            calls.incrementAndGet(); entered.countDown();
            try { assertTrue(release.await(3, TimeUnit.SECONDS)); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
            return "{\"success\":true}";
        });
        open();
        ToolRequest first = request("winner", "browser_click", "{}");
        ToolRequest second = request("loser", "browser_click", "{}");
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var running = workers.submit(() -> fixture.pipeline.invoke(first));
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                assertEquals(ToolStatus.UNKNOWN, fixture.pipeline.invoke(second).result().status());
                assertEquals(1, outcomes.size());
                assertEquals(ActionExecutionStatus.UNKNOWN, outcomes.getFirst().status());
                assertFalse(outcomes.getFirst().key().nodeId().startsWith("effect-"));
                assertEquals(1, calls.get());
            } finally { release.countDown(); }
            assertTrue(running.get(2, TimeUnit.SECONDS).result().isSuccess());
        }
    }

    @Test void confirmedClicksCanRepeatAndArrayOrderRemainsSignificant() {
        AtomicInteger calls = new AtomicInteger();
        Fixture fixture = fixture("browser_click", args -> "{\"click\":" + calls.incrementAndGet() + "}");
        open();
        assertTrue(fixture.pipeline.invoke(request("click-one", "browser_click", "{}")).text().contains("1"));
        assertTrue(fixture.pipeline.invoke(request("click-two", "browser_click", "{}")).text().contains("2"));
        assertEquals(2, calls.get(), "a confirmed click is not a cache entry for future input");

        Fixture unknown = fixture("browser_click", args -> { calls.incrementAndGet(); throw new IllegalStateException("lost reply"); });
        assertEquals(ToolStatus.UNKNOWN, unknown.pipeline.invoke(request("order-one", "browser_click", "{\"keys\":[1,2]}")).result().status());
        assertEquals(ToolStatus.UNKNOWN, unknown.pipeline.invoke(request("order-two", "browser_click", "{\"keys\":[2,1]}")).result().status());
        assertEquals(4, calls.get(), "JSON arrays encode an ordered operation");
    }

    @Test void newReadOperationReturnsFreshBusinessDataInsteadOfADeduplicationPlaceholder() {
        AtomicInteger observations = new AtomicInteger();
        Fixture fixture = fixture("browser_snapshot", args -> "{\"revision\":" + observations.incrementAndGet() + "}");
        open();
        assertEquals("{\"revision\":1}", fixture.pipeline.invoke(request("read-one", "browser_snapshot", "{}")).text());
        assertEquals("{\"revision\":2}", fixture.pipeline.invoke(request("read-two", "browser_snapshot", "{}")).text());
        assertEquals(2, observations.get());
    }

    @Test void eachChildHasItsOwnEffectBranchWhileReopeningTheSameChildPreservesQuarantine() {
        AtomicInteger calls = new AtomicInteger();
        Fixture fixture = fixture("browser_click", args -> { calls.incrementAndGet(); throw new IllegalStateException("lost reply"); });
        var parent = open();
        RunScope root = RunScope.capture();
        RunScope one = root.asSubagent("child-one", "worker", null, null);
        RunScope two = root.asSubagent("child-two", "worker", null, null);
        try (var ignored = one.bind()) {
            assertEquals(ToolStatus.UNKNOWN, fixture.pipeline.invoke(request("same-call", "browser_click", "{}")).result().status());
        }
        try (var ignored = two.bind()) {
            assertEquals(ToolStatus.UNKNOWN, fixture.pipeline.invoke(request("same-call", "browser_click", "{}")).result().status());
        }
        assertEquals(2, calls.get());
        ExecutionTurnContext.set(parent.forSubagent("child-one"));
        assertEquals(ToolStatus.UNKNOWN, fixture.pipeline.invoke(request("different-call", "browser_click", "{}")).result().status());
        assertEquals(2, calls.get());
        assertEquals(ACTION, parent.resolve("parent-call", "browser_click"), "children never consume parent bindings");
    }

    @Test void eitherTerminalWriteFailureKeepsTheEffectBlockedForDifferentCalls() {
        for (boolean failEffect : List.of(false, true)) {
            AtomicInteger calls = new AtomicInteger();
            InMemoryActionJournal authority = new InMemoryActionJournal();
            ActionJournal failing = new ActionJournal() {
                public Claim claim(ActionJournalKey key, String tool, String hash, boolean retry) {
                    return authority.claim(key, tool, hash, retry);
                }
                public Claim claimEffect(ActionJournalKey key, String tool, String hash) { return authority.claimEffect(key, tool, hash); }
                public void append(ActionJournalEntry entry) {
                    if (entry.key().nodeId().startsWith("effect-") == failEffect) throw new IllegalStateException("commit failed");
                    authority.append(entry);
                }
                public Optional<ActionJournalEntry> latest(ActionJournalKey key) { return authority.latest(key); }
                public List<ActionJournalEntry> unresolved() { return List.of(); }
            };
            Fixture fixture = fixture(failing, "browser_click", args -> "{\"click\":" + calls.incrementAndGet() + "}");
            open();
            assertEquals(ToolStatus.UNKNOWN, fixture.pipeline.invoke(request("one", "browser_click", "{}")).result().status());
            assertEquals(ToolStatus.UNKNOWN, fixture.pipeline.invoke(request("two", "browser_click", "{}")).result().status());
            assertEquals(ToolStatus.UNKNOWN, fixture.pipeline.invoke(request("one", "browser_click", "{}")).result().status());
            assertEquals(1, calls.get(), "neither a new nor the original operation can bypass incomplete persistence");
        }
    }

    @Test void unsupportedEffectClaimsFailClosedAndCloseTheOwnedOperationAsUnknown() {
        AtomicInteger calls = new AtomicInteger();
        InMemoryActionJournal authority = new InMemoryActionJournal();
        ActionJournal unsupported = new ActionJournal() {
            public Claim claim(ActionJournalKey key, String tool, String hash, boolean retry) { return authority.claim(key, tool, hash, retry); }
            public void append(ActionJournalEntry entry) { authority.append(entry); }
            public Optional<ActionJournalEntry> latest(ActionJournalKey key) { return authority.latest(key); }
            public List<ActionJournalEntry> unresolved() { return List.of(); }
        };
        Fixture fixture = fixture(unsupported, "browser_click", args -> "{\"click\":" + calls.incrementAndGet() + "}");
        open();
        ToolRequest request = request("blocked", "browser_click", "{}");
        assertEquals(ToolStatus.UNKNOWN, fixture.pipeline.invoke(request).result().status());
        assertEquals(ToolStatus.UNKNOWN, fixture.pipeline.invoke(request).result().status());
        assertEquals(0, calls.get());
    }

    @Test void operationBindingsStillRespectAnUnresolvedLegacyPlannerReceipt() {
        AtomicInteger calls = new AtomicInteger();
        InMemoryActionJournal journal = new InMemoryActionJournal();
        journal.append(new ActionJournalEntry(new ActionJournalKey(SESSION, "1", ACTION.nodeId(), ACTION.idempotencyKey()),
                "browser_click", "legacy", ActionExecutionStatus.UNKNOWN, 1, 1, "", "", ""));
        Fixture fixture = fixture(journal, "browser_click", args -> "{\"click\":" + calls.incrementAndGet() + "}");
        open();
        assertEquals(ToolStatus.UNKNOWN, fixture.pipeline.invoke(request("new-call", "browser_click", "{}")).result().status());
        assertEquals(0, calls.get());
    }

    private static ExecutionTurnContext.Scope open() {
        AgentLoop.setCurrentSession(SESSION);
        PermissionContext.force(SESSION, PermissionMode.ACCEPT_EDITS, ConfirmPolicy.AUTO, true);
        return ExecutionTurnContext.open(SESSION, "task", 1, List.of(ACTION), () -> true);
    }

    private static ToolRequest request(String callId, String tool, String arguments) {
        var scope = ExecutionTurnContext.current();
        return ToolRequest.of(scope.sessionId(), tool, arguments, 1, "fixture")
                .withActionBinding(scope.resolveInvocation(callId, tool));
    }

    private record Fixture(ToolPipeline pipeline) { }
    private static Fixture fixture(String tool, Function<String, String> handler) {
        return fixture(new InMemoryActionJournal(), tool, handler);
    }
    private static Fixture fixture(ActionJournal journal, String tool, Function<String, String> handler) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool, "fixture", Map.of(), handler);
        SessionPermissionStore permissions = new SessionPermissionStore();
        return new Fixture(new ToolPipeline(new ExecutionControl(60000, 50, 10000), new ToolHookChain(List.of()), permissions,
                journal, new ToolExecutionGuards(registry, new AgentToolsProperties(4)), registry,
                new ExecPolicyService("allow", "", permissions)));
    }
}
