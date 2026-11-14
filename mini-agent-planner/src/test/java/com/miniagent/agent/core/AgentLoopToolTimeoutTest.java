package com.miniagent.agent.core;

import com.miniagent.agent.execution.*;
import com.miniagent.agent.hook.ToolHookChain;
import com.miniagent.agent.permission.*;
import com.miniagent.agent.tool.*;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises actual AgentLoop -> permission -> locks -> registered handler execution. */
class AgentLoopToolTimeoutTest {
    private static final String SESSION = "handler-timeout";
    private static final String COMMAND = "{\"command\":\"git status\"}";

    @Test
    void ordinaryReadRespectsOneSecondActionCapAndJournalsTimeout() throws Exception {
        Fixture fixture = new Fixture();
        CountDownLatch interrupted = new CountDownLatch(1);
        fixture.registry.register("read_file", "read", Map.of(), args -> {
            assertEquals(SESSION, AgentLoop.getCurrentSession());
            assertEquals(1, LoopTurnContext.current().actionTimeoutSeconds());
            return slowHandler(interrupted);
        });

        long started = System.nanoTime();
        List<ToolResult> results = fixture.start(List.of(call("read_file", "{}")), false)
                .get(3, TimeUnit.SECONDS);

        assertEquals(ToolStatus.TIMEOUT, results.getFirst().status());
        assertTrue(elapsedMillis(started) < 2_100, "handler must not receive the approval TTL as extra budget");
        assertTrue(interrupted.await(1, TimeUnit.SECONDS));
        await(() -> fixture.journal.latestStatus("read_file") == ActionExecutionStatus.TIMEOUT);
    }

    @Test
    void approvalLongerThanActionCapStillLeavesFullHandlerBudget() throws Exception {
        Fixture fixture = new Fixture();
        AtomicInteger calls = new AtomicInteger();
        fixture.registry.register("exec_command", "exec", Map.of(), args -> {
            calls.incrementAndGet();
            sleep(350);
            return "{\"success\":true}";
        });
        Future<List<ToolResult>> running = fixture.start(List.of(call("exec_command", COMMAND)), false);
        fixture.awaitApproval();
        sleep(1_200);
        assertFalse(running.isDone());
        assertEquals(0, calls.get());

        fixture.approve();

        assertTrue(running.get(3, TimeUnit.SECONDS).getFirst().isSuccess());
        assertEquals(1, calls.get());
    }

    @Test
    void approvedHandlerStillTimesOutOneSecondAfterApproval() throws Exception {
        Fixture fixture = new Fixture();
        CountDownLatch interrupted = new CountDownLatch(1);
        fixture.registry.register("exec_command", "exec", Map.of(), args -> slowHandler(interrupted));
        Future<List<ToolResult>> running = fixture.start(List.of(call("exec_command", COMMAND)), false);
        fixture.awaitApproval();
        sleep(1_100);
        assertFalse(running.isDone());
        long approved = System.nanoTime();
        fixture.approve();

        assertEquals(ToolStatus.TIMEOUT, running.get(3, TimeUnit.SECONDS).getFirst().status());
        assertTrue(elapsedMillis(approved) < 2_100);
        assertTrue(interrupted.await(1, TimeUnit.SECONDS));
        await(() -> fixture.journal.latestStatus("exec_command") == ActionExecutionStatus.TIMEOUT);
    }

    @Test
    void earlierApprovalWaitCannotExtendLaterParallelHandlersDeadline() throws Exception {
        Fixture fixture = new Fixture();
        CountDownLatch readInterrupted = new CountDownLatch(1);
        fixture.registry.register("exec_command", "exec", Map.of(), args -> "{\"success\":true}");
        fixture.registry.register("read_file", "read", Map.of(), args -> slowHandler(readInterrupted));
        Future<List<ToolResult>> running = fixture.start(List.of(
                call("exec_command", COMMAND), call("read_file", "{}")), true);
        fixture.awaitApproval();

        assertTrue(readInterrupted.await(2, TimeUnit.SECONDS),
                "second handler must time out while ordered collection still waits for first approval");
        assertFalse(running.isDone());
        fixture.approve();

        List<ToolResult> results = running.get(3, TimeUnit.SECONDS);
        assertTrue(results.getFirst().isSuccess());
        assertEquals(ToolStatus.TIMEOUT, results.get(1).status());
    }

    @Test
    void pauseBeforeHandlerDoesNotConsumeItsExecutionBudget() throws Exception {
        Fixture fixture = new Fixture();
        AtomicInteger calls = new AtomicInteger();
        fixture.registry.register("read_file", "read", Map.of(), args -> {
            calls.incrementAndGet();
            sleep(350);
            return "{\"success\":true}";
        });
        fixture.control.pause(SESSION);
        Future<List<ToolResult>> running = fixture.start(List.of(call("read_file", "{}")), false);
        await(() -> fixture.control.isPausedAtCheckpoint(SESSION));
        sleep(1_200);
        assertFalse(running.isDone());
        assertEquals(0, calls.get());

        fixture.control.resume(SESSION);

        assertTrue(running.get(3, TimeUnit.SECONDS).getFirst().isSuccess());
    }

    @Test
    void cancellingDuringApprovalRemovesPendingGrantAndNeverRunsHandler() throws Exception {
        Fixture fixture = new Fixture();
        AtomicInteger calls = new AtomicInteger();
        fixture.registry.register("exec_command", "exec", Map.of(), args -> {
            calls.incrementAndGet();
            return "{\"success\":true}";
        });
        Future<List<ToolResult>> running = fixture.start(List.of(call("exec_command", COMMAND)), false);
        fixture.awaitApproval();

        fixture.control.cancel(SESSION);

        assertEquals(ToolStatus.CANCELLED, running.get(2, TimeUnit.SECONDS).getFirst().status());
        await(() -> !fixture.store.hasAttachedAskResume(SESSION, fixture.grant.get().token()));
        assertTrue(fixture.store.grantAskTokenForResume(SESSION, fixture.grant.get().token(), "exec_command").isEmpty());
        assertEquals(0, calls.get());
    }

    @Test
    void runDeadlineDuringApprovalRemovesPendingGrantAndNeverRunsHandler() throws Exception {
        Fixture fixture = new Fixture(1_000);
        AtomicInteger calls = new AtomicInteger();
        fixture.registry.register("exec_command", "exec", Map.of(), args -> {
            calls.incrementAndGet();
            return "{\"success\":true}";
        });
        fixture.control.start(SESSION);
        Future<List<ToolResult>> running = fixture.start(List.of(call("exec_command", COMMAND)), false);
        fixture.awaitApproval();

        ToolResult result = running.get(3, TimeUnit.SECONDS).getFirst();

        assertEquals(ToolErrorCode.TIMEOUT, result.errorCode());
        await(() -> !fixture.store.hasAttachedAskResume(SESSION, fixture.grant.get().token()));
        assertTrue(fixture.store.grantAskTokenForResume(SESSION, fixture.grant.get().token(), "exec_command").isEmpty());
        assertEquals(0, calls.get());
    }

    @Test
    void runDeadlineStopsUncooperativeReadBeforeItsHandlerBudget() throws Exception {
        Fixture fixture = new Fixture(1_000);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        fixture.registry.register("read_file", "read", Map.of(), args -> {
            started.countDown();
            while (release.getCount() > 0) {
                try { release.await(); }
                catch (InterruptedException ignored) { /* Simulate an uncooperative SDK. */ }
            }
            return "{\"success\":true}";
        });
        fixture.control.start(SESSION);
        ToolRequest request = ToolRequest.of(SESSION, "read_file", "{}", 0, "run-deadline")
                .withExecutionBudget(20);
        FutureTask<ToolInvocation> future = new FutureTask<>(() -> fixture.pipeline.invoke(request));
        Thread.ofVirtual().start(future);
        try {
            assertTrue(started.await(1, TimeUnit.SECONDS));
            ToolInvocation invocation = ReflectionTestUtils.invokeMethod(
                    fixture.loop, "awaitToolInvocation", future, request);

            assertNotNull(invocation);
            assertEquals(ToolErrorCode.TIMEOUT, invocation.result().errorCode());
            assertFalse(request.budget().expired(), "the run deadline must not wait for the handler budget");
            assertEquals(ActionExecutionStatus.TIMEOUT, fixture.journal.latestStatus("read_file"));
        } finally {
            release.countDown();
        }
    }

    @Test
    void unavailableControlStoreCannotTurnAnUnconfirmedWriteIntoRetryableFailure() {
        Fixture fixture = new Fixture();
        ReflectionTestUtils.setField(fixture.loop, "executionControl", new ExecutionControl(60_000, 20, 10_000) {
            @Override public StopReason stopReason(String sessionId) {
                throw new IllegalStateException("Redis unavailable");
            }
        });
        ToolRequest request = ToolRequest.of(SESSION, "write_file", "{}", 0, "unconfirmed-write")
                .withExecutionBudget(20);
        CompletableFuture<ToolInvocation> pending = new CompletableFuture<>();

        ToolInvocation invocation = ReflectionTestUtils.invokeMethod(
                fixture.loop, "awaitToolInvocation", pending, request);

        assertNotNull(invocation);
        assertEquals(ToolStatus.UNKNOWN, invocation.result().status());
        assertFalse(invocation.result().retriable());
        assertTrue(pending.isCancelled());
    }

    @Test
    void ignoredInterruptKeepsResourceLockUntilOriginalHandlerExits() throws Exception {
        Fixture fixture = new Fixture();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch firstInterrupted = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        fixture.registry.register("read_file", "read", Map.of(), args -> {
            if (calls.incrementAndGet() == 1) {
                while (release.getCount() > 0) {
                    try { release.await(); }
                    catch (InterruptedException ignored) { firstInterrupted.countDown(); }
                }
            } else secondStarted.countDown();
            return "{\"success\":true}";
        });
        Future<List<ToolResult>> first = fixture.start(List.of(call("read_file", "{\"path\":\"same\"}")), false);
        Future<ToolInvocation> second = null;
        try {
            assertEquals(ToolStatus.TIMEOUT, first.get(3, TimeUnit.SECONDS).getFirst().status());
            assertTrue(firstInterrupted.await(1, TimeUnit.SECONDS));
            ToolRequest next = ToolRequest.of(SESSION, "read_file", "{\"path\":\"same\"}", 1, "second")
                    .withExecutionBudget(1);
            FutureTask<ToolInvocation> task = new FutureTask<>(() -> fixture.pipeline.invoke(next));
            Thread.ofVirtual().start(task);
            second = task;
            assertFalse(secondStarted.await(1_100, TimeUnit.MILLISECONDS),
                    "timeout must not release a lock still owned by an interrupted handler");
            release.countDown();
            assertTrue(second.get(3, TimeUnit.SECONDS).result().isSuccess(),
                    "lock wait must not consume the next handler's own deadline");
        } finally {
            release.countDown();
            if (second != null && !second.isDone()) second.cancel(true);
        }
    }

    @Test
    void completedHandlerCancelsItsDeadlineAndNeverInterruptsReusedThread() throws Exception {
        Fixture fixture = new Fixture();
        fixture.registry.register("read_file", "read", Map.of(), args -> "{\"success\":true}");
        ToolRequest request = ToolRequest.of(SESSION, "read_file", "{}", 0, "timer-cleanup")
                .withExecutionBudget(1);
        FutureTask<Boolean> running = new FutureTask<>(() -> {
            assertTrue(fixture.pipeline.invoke(request).result().isSuccess());
            Thread.sleep(1_200);
            return !Thread.currentThread().isInterrupted();
        });
        Thread.ofVirtual().start(running);

        assertTrue(running.get(3, TimeUnit.SECONDS));
        assertFalse(request.budget().expired());
    }

    private static String slowHandler(CountDownLatch interrupted) {
        try { Thread.sleep(2_200); }
        catch (InterruptedException e) {
            interrupted.countDown();
            Thread.currentThread().interrupt();
            return "{\"error\":\"handler was interrupted\"}";
        }
        return "{\"success\":true}";
    }

    private static void sleep(long millis) {
        try { Thread.sleep(millis); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
    }

    private static long elapsedMillis(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), "condition was not met");
    }

    private static ToolExecutionRequest call(String name, String arguments) {
        return ToolExecutionRequest.builder().id(UUID.randomUUID().toString()).name(name).arguments(arguments).build();
    }

    private static class Fixture {
        final ToolRegistry registry = new ToolRegistry();
        final AtomicReference<AskGrant> grant = new AtomicReference<>();
        final CountDownLatch approvalAttached = new CountDownLatch(1);
        final SessionPermissionStore store = new SessionPermissionStore() {
            @Override public AskGrant issueAskGrant(String sid, String name, String args, long ttl) {
                AskGrant issued = super.issueAskGrant(sid, name, args, ttl);
                grant.set(issued);
                return issued;
            }
            @Override public boolean attachAskResume(String sid, String token) {
                boolean attached = super.attachAskResume(sid, token);
                if (attached) approvalAttached.countDown();
                return attached;
            }
        };
        final ExecutionControl control;
        final RecordingJournal journal = new RecordingJournal();
        final ToolExecutionGuards guards = new ToolExecutionGuards(registry, new AgentToolsProperties(4));
        final ToolPipeline pipeline;
        final AgentLoop loop = new AgentLoop();

        Fixture() {
            this(60_000);
        }

        Fixture(long deadlineMillis) {
            control = new ExecutionControl(deadlineMillis, 20, 10_000);
            pipeline = new ToolPipeline(control, new ToolHookChain(List.of()), store,
                    journal, guards, registry, new ExecPolicyService("ask", "", store));
            ReflectionTestUtils.setField(loop, "toolPipeline", pipeline);
            ReflectionTestUtils.setField(loop, "toolExecutionGuards", guards);
            ReflectionTestUtils.setField(loop, "executionControl", control);
        }

        Future<List<ToolResult>> start(List<ToolExecutionRequest> calls, boolean parallel) {
            FutureTask<List<ToolResult>> task = new FutureTask<>(() -> {
                AgentLoop.setCurrentSession(SESSION);
                LoopTurnContext.set(oneSecondPolicy());
                PermissionContext.force(SESSION, PermissionMode.DEFAULT, ConfirmPolicy.DANGEROUS, true);
                try {
                    Class<?> type = Class.forName("com.miniagent.agent.core.AgentLoop$LoopState");
                    var constructor = type.getDeclaredConstructor();
                    constructor.setAccessible(true);
                    Object state = constructor.newInstance();
                    ReflectionTestUtils.setField(state, "allowedToolsForTurn", Set.of("read_file", "exec_command"));
                    List<ChatMessage> messages = new ArrayList<>();
                    if (parallel) ReflectionTestUtils.invokeMethod(loop, "executeToolCallsParallel", calls, messages, state, Map.of());
                    else ReflectionTestUtils.invokeMethod(loop, "executeToolCallSingle", calls.getFirst(), messages, state, null);
                    return messages.stream().map(message -> ToolResult.fromLegacy(
                            ((ToolExecutionResultMessage) message).text().split("\\n\\n", 2)[0])).toList();
                } finally {
                    AgentLoop.clearCurrentSession();
                    LoopTurnContext.clear();
                    PermissionContext.clear();
                }
            });
            Thread.ofVirtual().start(task);
            return task;
        }

        void awaitApproval() throws Exception { assertTrue(approvalAttached.await(3, TimeUnit.SECONDS)); }
        void approve() { assertTrue(store.grantAskTokenForResume(SESSION, grant.get().token(), "exec_command").isPresent()); }
    }

    private static LoopTurnPolicy oneSecondPolicy() {
        return new LoopTurnPolicy() {
            public List<String> allowedTools() { return List.of("read_file", "exec_command"); }
            public boolean forceToolsOnly() { return false; }
            public boolean hardGate() { return false; }
            public String denyTool(String name) { return null; }
            public String denyTodoArgs(String args) { return null; }
            public String focusLabel() { return ""; }
            public Set<Integer> focusTodoIds() { return Set.of(); }
            public void markDrift() { }
            public int driftHits() { return 0; }
            public boolean consumeDrift() { return false; }
            public int actionTimeoutSeconds() { return 1; }
        };
    }

    private static class RecordingJournal implements ActionJournal {
        final Map<ActionJournalKey, ActionJournalEntry> entries = new ConcurrentHashMap<>();
        public Claim claim(ActionJournalKey key, String tool, String hash, boolean retry) {
            return com.miniagent.agent.execution.InMemoryActionJournal.claimEntry(entries, key, tool, hash, retry);
        }
        public Claim claimEffect(ActionJournalKey key, String tool, String hash) {
            return com.miniagent.agent.execution.InMemoryActionJournal.claimEffectEntry(entries, key, tool, hash);
        }
        public void append(ActionJournalEntry entry) { entries.put(entry.key(), entry); }
        public Optional<ActionJournalEntry> latest(ActionJournalKey key) { return Optional.ofNullable(entries.get(key)); }
        public List<ActionJournalEntry> unresolved() { return List.of(); }
        ActionExecutionStatus latestStatus(String tool) {
            return entries.values().stream().filter(entry -> entry.toolName().equals(tool))
                    .map(ActionJournalEntry::status).findFirst().orElse(null);
        }
    }
}
