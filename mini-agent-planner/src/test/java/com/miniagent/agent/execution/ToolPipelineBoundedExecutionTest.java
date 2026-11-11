package com.miniagent.agent.execution;

import com.miniagent.agent.core.ExecutionControl;
import com.miniagent.agent.hook.ToolHookChain;
import com.miniagent.agent.hook.ToolHook;
import com.miniagent.agent.hook.ToolHookContext;
import com.miniagent.agent.permission.ExecPolicyService;
import com.miniagent.agent.permission.AskGrant;
import com.miniagent.agent.permission.SessionPermissionStore;
import com.miniagent.agent.tool.ToolErrorCode;
import com.miniagent.agent.tool.ToolRegistry;
import com.miniagent.agent.tool.ToolResult;
import com.miniagent.agent.tool.ToolStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ToolPipelineBoundedExecutionTest {
    private static final String SESSION = "bounded-pipeline";
    private static final String ARGUMENTS = "{\"path\":\"same-file\"}";

    @Test
    void ignoredInterruptReturnsUnknownAndLateSuccessCannotReplaceJournalTerminal() throws Exception {
        Fixture fixture = new Fixture(60_000);
        BlockingWrite write = new BlockingWrite();
        fixture.registry.register("write_file", "write", Map.of(), ignored -> write.execute());
        ToolRequest request = ToolRequest.of(SESSION, "write_file", ARGUMENTS, 0, "one")
                .withExecutionBudget(1);
        FutureTask<ToolInvocation> running = start(() -> fixture.pipeline.invoke(request));
        try {
            assertTrue(write.started.await(1, TimeUnit.SECONDS));
            ToolInvocation result = running.get(2, TimeUnit.SECONDS);
            assertEquals(ToolStatus.UNKNOWN, result.result().status());
            assertEquals(ActionExecutionStatus.UNKNOWN, fixture.journal.status());
            assertTrue(fixture.pipeline.invoke(request).result().status() == ToolStatus.UNKNOWN);
            assertEquals(1, write.calls.get(), "an unresolved side effect cannot be replayed");

            write.release.countDown();
            assertTrue(write.exited.await(1, TimeUnit.SECONDS));
            Thread.sleep(100);
            assertEquals(ActionExecutionStatus.UNKNOWN, fixture.journal.status(),
                    "a handler that returns late must not overwrite the caller's terminal record");
        } finally {
            write.release.countDown();
        }
    }

    @Test
    void timedOutHandlerRetainsItsResourceLockUntilItActuallyExits() throws Exception {
        Fixture fixture = new Fixture(60_000);
        BlockingWrite write = new BlockingWrite();
        CountDownLatch secondStarted = new CountDownLatch(1);
        fixture.registry.register("write_file", "write", Map.of(), ignored -> {
            if (write.calls.get() == 0) return write.execute();
            secondStarted.countDown();
            return "{\"success\":true}";
        });
        FutureTask<ToolInvocation> first = start(() -> fixture.pipeline.invoke(
                ToolRequest.of(SESSION, "write_file", ARGUMENTS, 0, "one").withExecutionBudget(1)));
        try {
            assertTrue(write.started.await(1, TimeUnit.SECONDS));
            assertEquals(ToolStatus.UNKNOWN, first.get(2, TimeUnit.SECONDS).result().status());
            FutureTask<ToolInvocation> second = start(() -> fixture.pipeline.invoke(
                    ToolRequest.of(SESSION, "write_file", ARGUMENTS, 0, "two").withExecutionBudget(1)));
            assertFalse(secondStarted.await(300, TimeUnit.MILLISECONDS),
                    "a timed-out handler must keep the lock protecting its pending side effects");
            write.release.countDown();
            assertTrue(second.get(2, TimeUnit.SECONDS).result().isSuccess());
        } finally {
            write.release.countDown();
        }
    }

    @Test
    void cancellationReturnsPromptlyAndJournalsUnresolvedSideEffects() throws Exception {
        Fixture fixture = new Fixture(60_000);
        fixture.control.start(SESSION);
        BlockingWrite write = new BlockingWrite();
        fixture.registry.register("write_file", "write", Map.of(), ignored -> write.execute());
        FutureTask<ToolInvocation> running = start(() -> fixture.pipeline.invoke(
                ToolRequest.of(SESSION, "write_file", ARGUMENTS, 0, "one").withExecutionBudget(20)));
        try {
            assertTrue(write.started.await(1, TimeUnit.SECONDS));
            fixture.control.cancel(SESSION);
            ToolInvocation result = running.get(1, TimeUnit.SECONDS);
            assertEquals(ToolInvocation.Outcome.CONTROL_STOP, result.outcome());
            assertEquals(ToolErrorCode.OUTCOME_UNKNOWN, result.result().errorCode());
            assertEquals(ExecutionControl.StopReason.CANCELLED, fixture.control.stopReason(SESSION));
            assertEquals(ActionExecutionStatus.UNKNOWN, fixture.journal.status());
        } finally {
            write.release.countDown();
        }
    }

    @Test
    void runDeadlineStopsAHandlerWhoseOwnBudgetIsLonger() throws Exception {
        Fixture fixture = new Fixture(1_000);
        fixture.control.start(SESSION);
        BlockingWrite write = new BlockingWrite();
        fixture.registry.register("write_file", "write", Map.of(), ignored -> write.execute());
        FutureTask<ToolInvocation> running = start(() -> fixture.pipeline.invoke(
                ToolRequest.of(SESSION, "write_file", ARGUMENTS, 0, "one").withExecutionBudget(20)));
        try {
            assertTrue(write.started.await(1, TimeUnit.SECONDS));
            ToolInvocation result = running.get(2, TimeUnit.SECONDS);
            assertEquals(ToolInvocation.Outcome.CONTROL_STOP, result.outcome());
            assertEquals(ToolErrorCode.OUTCOME_UNKNOWN, result.result().errorCode());
            assertEquals(ExecutionControl.StopReason.DEADLINE_EXCEEDED, fixture.control.stopReason(SESSION));
            assertEquals(ActionExecutionStatus.UNKNOWN, fixture.journal.status());
        } finally {
            write.release.countDown();
        }
    }

    @Test
    void directBoundApprovalWaitStopsAtRunDeadlineAndConsumesPendingToken() throws Exception {
        Fixture fixture = new Fixture(1_000, "ask");
        fixture.control.start(SESSION);
        AtomicInteger calls = new AtomicInteger();
        fixture.registry.register("exec_command", "exec", Map.of(), ignored -> {
            calls.incrementAndGet();
            return "{\"success\":true}";
        });
        FutureTask<ToolInvocation> running = start(() -> fixture.pipeline.invoke(
                ToolRequest.bound(SESSION, "exec_command", "{\"command\":\"echo safe\"}")
                        .withApprovalWait().withExecutionBudget(20)));

        assertTrue(fixture.store.attached.await(1, TimeUnit.SECONDS));
        ToolInvocation result = running.get(2, TimeUnit.SECONDS);
        assertEquals(ToolInvocation.Outcome.CONTROL_STOP, result.outcome());
        assertEquals(ToolErrorCode.TIMEOUT, result.result().errorCode());
        String token = fixture.store.grant.get().token();
        assertFalse(fixture.store.hasAttachedAskResume(SESSION, token));
        assertTrue(fixture.store.grantAskTokenForResume(SESSION, token, "exec_command").isEmpty());
        assertEquals(0, calls.get());
    }

    @Test
    void directBoundApprovalWaitObservesCancellationWithoutAnOuterAgentLoop() throws Exception {
        Fixture fixture = new Fixture(60_000, "ask");
        fixture.control.start(SESSION);
        AtomicInteger calls = new AtomicInteger();
        fixture.registry.register("exec_command", "exec", Map.of(), ignored -> {
            calls.incrementAndGet();
            return "{\"success\":true}";
        });
        FutureTask<ToolInvocation> running = start(() -> fixture.pipeline.invoke(
                ToolRequest.bound(SESSION, "exec_command", "{\"command\":\"echo safe\"}")
                        .withApprovalWait().withExecutionBudget(20)));

        assertTrue(fixture.store.attached.await(1, TimeUnit.SECONDS));
        fixture.control.cancel(SESSION);
        ToolInvocation result = running.get(1, TimeUnit.SECONDS);
        assertEquals(ToolInvocation.Outcome.CONTROL_STOP, result.outcome());
        assertEquals(ToolErrorCode.CANCELLED, result.result().errorCode());
        String token = fixture.store.grant.get().token();
        assertTrue(fixture.store.grantAskTokenForResume(SESSION, token, "exec_command").isEmpty());
        assertEquals(0, calls.get());
    }

    @Test
    void slowStopCheckDoesNotHoldTheGlobalApprovalMonitor() throws Exception {
        TrackingStore store = new TrackingStore();
        AskGrant grant = store.issueAskGrant(SESSION, "exec_command", "{}", 5_000);
        assertTrue(store.attachAskResume(SESSION, grant.token()));
        CountDownLatch checking = new CountDownLatch(1);
        CountDownLatch releaseCheck = new CountDownLatch(1);
        FutureTask<Optional<AskGrant>> running = start(() -> store.awaitAskApproval(
                SESSION, grant.token(), 5_000, () -> {
                    checking.countDown();
                    try { releaseCheck.await(); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    return false;
                }));
        try {
            assertTrue(checking.await(1, TimeUnit.SECONDS));
            FutureTask<Optional<AskGrant>> approval = start(() -> store.grantAskTokenForResume(
                    SESSION, grant.token(), "exec_command"));
            assertTrue(approval.get(1, TimeUnit.SECONDS).isPresent(),
                    "the potentially remote stop check cannot block other approval operations");
            releaseCheck.countDown();
            assertTrue(running.get(1, TimeUnit.SECONDS).isPresent());
        } finally {
            releaseCheck.countDown();
            if (!running.isDone()) running.cancel(true);
        }
    }

    @Test
    void failedStopCheckAbandonsThePendingApprovalToken() {
        TrackingStore store = new TrackingStore();
        AskGrant grant = store.issueAskGrant(SESSION, "exec_command", "{}", 5_000);
        assertTrue(store.attachAskResume(SESSION, grant.token()));

        assertThrows(IllegalStateException.class, () -> store.awaitAskApproval(
                SESSION, grant.token(), 5_000, () -> { throw new IllegalStateException("redis unavailable"); }));

        assertFalse(store.hasAttachedAskResume(SESSION, grant.token()));
        assertTrue(store.grantAskTokenForResume(SESSION, grant.token(), "exec_command").isEmpty());
    }

    @Test
    void textPostHookCannotUpgradeAnUnknownExecutionToSuccess() {
        ToolHook optimisticHook = new ToolHook() {
            public String name() { return "optimistic-hook"; }
            public String after(ToolHookContext context, String result) {
                return "{\"success\":true}";
            }
        };
        Fixture fixture = new Fixture(60_000, "allow", List.of(optimisticHook));
        fixture.registry.registerStructured("write_file", "write", Map.of(), ignored ->
                ToolResult.unknown("remote write is unresolved", null));

        ToolInvocation result = fixture.pipeline.invoke(
                ToolRequest.of(SESSION, "write_file", ARGUMENTS, 0, "one"));

        assertEquals(ToolStatus.UNKNOWN, result.result().status());
        assertEquals(ToolStatus.UNKNOWN, ToolResult.fromLegacy(result.text()).status());
        assertEquals(ActionExecutionStatus.UNKNOWN, fixture.journal.status());
    }

    private static <T> FutureTask<T> start(java.util.concurrent.Callable<T> operation) {
        FutureTask<T> task = new FutureTask<>(operation);
        Thread.ofVirtual().start(task);
        return task;
    }

    private static class BlockingWrite {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch exited = new CountDownLatch(1);
        final AtomicInteger calls = new AtomicInteger();

        String execute() {
            calls.incrementAndGet();
            started.countDown();
            while (release.getCount() > 0) {
                try { release.await(); }
                catch (InterruptedException ignored) { /* Simulate an uncooperative SDK. */ }
            }
            exited.countDown();
            return "{\"success\":true}";
        }
    }

    private static class Fixture {
        final ToolRegistry registry = new ToolRegistry();
        final ExecutionControl control;
        final RecordingJournal journal = new RecordingJournal();
        final TrackingStore store = new TrackingStore();
        final ToolPipeline pipeline;

        Fixture(long deadlineMillis) {
            this(deadlineMillis, "allow");
        }

        Fixture(long deadlineMillis, String execPolicy) {
            this(deadlineMillis, execPolicy, List.of());
        }

        Fixture(long deadlineMillis, String execPolicy, List<ToolHook> hooks) {
            control = new ExecutionControl(deadlineMillis, 20, 10_000);
            ToolExecutionGuards guards = new ToolExecutionGuards(registry, new AgentToolsProperties(4));
            pipeline = new ToolPipeline(control, new ToolHookChain(hooks), store,
                    journal, guards, registry, new ExecPolicyService(execPolicy, "", store));
        }
    }

    private static class TrackingStore extends SessionPermissionStore {
        final AtomicReference<AskGrant> grant = new AtomicReference<>();
        final CountDownLatch attached = new CountDownLatch(1);

        @Override public AskGrant issueAskGrant(String sid, String name, String args, long ttl) {
            AskGrant issued = super.issueAskGrant(sid, name, args, ttl);
            grant.set(issued);
            return issued;
        }

        @Override public boolean attachAskResume(String sid, String token) {
            boolean result = super.attachAskResume(sid, token);
            if (result) attached.countDown();
            return result;
        }
    }

    private static class RecordingJournal implements ActionJournal {
        final Map<ActionJournalKey, ActionJournalEntry> entries = new ConcurrentHashMap<>();
        public void append(ActionJournalEntry entry) { entries.put(entry.key(), entry); }
        public Optional<ActionJournalEntry> latest(ActionJournalKey key) {
            return Optional.ofNullable(entries.get(key));
        }
        public List<ActionJournalEntry> unresolved() { return List.of(); }
        ActionExecutionStatus status() { return entries.values().iterator().next().status(); }
    }
}
