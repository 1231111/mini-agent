package com.miniagent.agent.execution;

import com.miniagent.agent.core.ExecutionControl;
import com.miniagent.agent.core.ExecutionTurnContext;
import com.miniagent.agent.core.LoopTurnContext;
import com.miniagent.agent.core.SessionEventCenter;
import com.miniagent.agent.desktop.DesktopInteractionService;
import com.miniagent.agent.hook.ToolHookChain;
import com.miniagent.agent.permission.AskGrant;
import com.miniagent.agent.permission.ConfirmPolicy;
import com.miniagent.agent.permission.ExecPolicyService;
import com.miniagent.agent.permission.PermissionContext;
import com.miniagent.agent.permission.PermissionMode;
import com.miniagent.agent.permission.SessionPermissionStore;
import com.miniagent.agent.tool.ToolErrorCode;
import com.miniagent.agent.tool.ToolRegistry;
import com.miniagent.agent.tool.ToolStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Pipeline-only regression tests: no native input or real desktop is touched. */
class ToolPipelineDesktopTest {
    private static final String SESSION = "desktop-pipeline";
    private static final String CLICK = "{\"observationId\":\"screen-1\",\"action\":\"click\",\"x\":20,\"y\":40}";

    @AfterEach
    void clearContexts() {
        LoopTurnContext.clear();
        ExecutionTurnContext.clear();
        PermissionContext.clear();
        PermissionContext.bindStore(null);
    }

    @Test
    void defaultApprovalIsBoundToTheExactObservationAndActionArguments() {
        Fixture fixture = new Fixture();
        AtomicInteger calls = new AtomicInteger();
        fixture.registerAction(calls);
        ToolRequest request = ToolRequest.of(SESSION, "desktop_act", CLICK, 0, "one");

        assertEquals(ToolInvocation.Outcome.PERMISSION_ASK, fixture.pipeline.invoke(request).outcome());
        assertEquals(0, calls.get());
        AskGrant grant = fixture.permissions.grant.get();
        assertTrue(fixture.permissions.grantAskToken(SESSION, grant.token(), "desktop_act").isPresent());
        assertTrue(fixture.pipeline.invoke(request).result().isSuccess());
        assertEquals(1, calls.get());

        assertEquals(ToolInvocation.Outcome.PERMISSION_ASK, fixture.pipeline.invoke(ToolRequest.of(
                SESSION, "desktop_act", CLICK.replace("screen-1", "screen-2"), 0, "two")).outcome());
        assertEquals(ToolInvocation.Outcome.PERMISSION_ASK, fixture.pipeline.invoke(ToolRequest.of(
                SESSION, "desktop_act", CLICK.replace("20", "21"), 0, "three")).outcome());
        assertEquals(1, calls.get(), "approving a click cannot approve a later screen or a different position");
    }

    @Test
    void desktopApprovalExpiresAtObservationDeadlineInsteadOfTheFiveMinuteDefault() throws Exception {
        Fixture fixture = new Fixture();
        SessionEventCenter events = mock(SessionEventCenter.class);
        fixture.pipeline.setEventCenter(events);
        AtomicInteger calls = new AtomicInteger();
        fixture.registerAction(calls);
        assertEquals(ToolInvocation.Outcome.PERMISSION_ASK, fixture.pipeline.invoke(
                ToolRequest.of(SESSION, "desktop_act", CLICK, 0, "one")).outcome());
        assertEquals(fixture.observationExpiry.get().toEpochMilli(),
                fixture.permissions.grant.get().expiresAtEpochMillis());
        var payload = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(events).publish(eq(SESSION), eq("permission_ask"), payload.capture());
        assertEquals(fixture.observationExpiry.get().toEpochMilli(), new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(payload.getValue().toString()).path("expiresAt").asLong(),
                "the UI countdown must use the same actual deadline as the server token");
        assertEquals(0, calls.get());
    }

    @Test
    void shorterConfiguredApprovalWindowIsStillRespected() {
        Fixture fixture = new Fixture();
        org.springframework.test.util.ReflectionTestUtils.setField(fixture.pipeline, "askGrantTtlSeconds", 1L);
        fixture.registerAction(new AtomicInteger());
        long before = System.currentTimeMillis();
        fixture.pipeline.invoke(ToolRequest.of(SESSION, "desktop_act", CLICK, 0, "one"));
        long expiry = fixture.permissions.grant.get().expiresAtEpochMillis();
        assertTrue(expiry >= before + 1_000L);
        assertTrue(expiry <= System.currentTimeMillis() + 1_000L);
        assertTrue(expiry < fixture.observationExpiry.get().toEpochMilli());
    }

    @Test
    void staleObservationDoesNotOfferApprovalOrDispatchInput() {
        Fixture fixture = new Fixture();
        AtomicInteger calls = new AtomicInteger();
        fixture.registerAction(calls);
        fixture.observationExpiry.set(Instant.now().minusMillis(1));
        ToolInvocation result = fixture.pipeline.invoke(ToolRequest.of(SESSION, "desktop_act", CLICK, 0, "one"));
        assertEquals(ToolErrorCode.CONFLICT, result.result().errorCode());
        assertTrue(result.text().contains("desktop_observe"));
        assertNull(fixture.permissions.grant.get());
        assertEquals(0, calls.get());
    }

    @Test
    void expiredObservationApprovalWakesWaiterAndCannotBeApprovedLater() throws Exception {
        Fixture fixture = new Fixture();
        AtomicInteger calls = new AtomicInteger();
        fixture.registerAction(calls);
        fixture.observationExpiry.set(Instant.ofEpochMilli(System.currentTimeMillis() + 700L));
        FutureTask<ToolInvocation> running = start(() -> fixture.pipeline.invoke(
                ToolRequest.of(SESSION, "desktop_act", CLICK, 0, "one").withApprovalWait()));
        try {
            assertTrue(fixture.permissions.attached.await(1, TimeUnit.SECONDS));
            AskGrant grant = fixture.permissions.grant.get();
            assertEquals(fixture.observationExpiry.get().toEpochMilli(), grant.expiresAtEpochMillis(),
                    "sub-second observation lifetime must not be rounded up to the normal TTL floor");
            assertEquals(ToolInvocation.Outcome.PERMISSION_DENIED, running.get(2, TimeUnit.SECONDS).outcome());
            assertFalse(fixture.permissions.hasAttachedAskResume(SESSION, grant.token()));
            assertTrue(fixture.permissions.grantAskTokenForResume(SESSION, grant.token(), "desktop_act").isEmpty());
            assertEquals(0, calls.get());
        } finally { running.cancel(true); }
    }

    @Test
    void liveApprovalResumesTheOriginalDesktopInvocationExactlyOnceWithItsOriginalArguments() throws Exception {
        Fixture fixture = new Fixture();
        CountDownLatch actionStarted = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> received = new AtomicReference<>();
        fixture.registry.register("desktop_act", "act", Map.of(), arguments -> {
            calls.incrementAndGet();
            received.set(arguments);
            actionStarted.countDown();
            return "{\"success\":true}";
        });
        ToolRequest original = ToolRequest.of(SESSION, "desktop_act", CLICK, 0, "original").withApprovalWait();
        FutureTask<ToolInvocation> running = start(() -> fixture.pipeline.invoke(original));
        try {
            assertTrue(fixture.permissions.attached.await(2, TimeUnit.SECONDS));
            AskGrant grant = fixture.permissions.grant.get();
            assertNotNull(grant);
            assertTrue(fixture.permissions.hasAttachedAskResume(SESSION, grant.token()));
            assertEquals(SessionPermissionStore.argumentDigest("desktop_act", CLICK), grant.argumentsDigest());
            assertNotEquals(SessionPermissionStore.argumentDigest("desktop_act", CLICK.replace("screen-1", "screen-2")),
                    grant.argumentsDigest(), "the approval binds the exact observed screen");
            assertNotEquals(SessionPermissionStore.argumentDigest("desktop_act", CLICK.replace("20", "21")),
                    grant.argumentsDigest(), "the approval binds the exact action coordinates");
            assertFalse(actionStarted.await(100, TimeUnit.MILLISECONDS), "waiting for approval must not dispatch input");
            assertFalse(running.isDone(), "the original tool invocation stays live instead of returning PERM_ASK");
            assertEquals(0, calls.get());
            assertNull(received.get());
            assertTrue(fixture.permissions.grantAskTokenForResume("other-chat", grant.token(), "desktop_act").isEmpty());
            assertTrue(fixture.permissions.grantAskTokenForResume(SESSION, grant.token(), "desktop_release").isEmpty());

            // Same entry point as the production controller; its implementation requires a live attached waiter.
            assertTrue(fixture.permissions.grantAskTokenForResume(SESSION, grant.token(), "desktop_act").isPresent());

            ToolInvocation result = running.get(2, TimeUnit.SECONDS);
            assertEquals(ToolInvocation.Outcome.EXECUTED, result.outcome());
            assertTrue(result.result().isSuccess());
            assertEquals(CLICK, received.get(), "resume executes the frozen invocation, without new model parameters");
            assertEquals(1, calls.get());
            assertEquals(ActionExecutionStatus.SUCCEEDED, fixture.journal.status("desktop_act"));
            assertFalse(fixture.permissions.hasAttachedAskResume(SESSION, grant.token()));
            assertFalse(fixture.permissions.isAskGranted(SESSION, "desktop_act", CLICK),
                    "one live approval must not persist a replayable argument grant");
            assertTrue(fixture.permissions.grantAskTokenForResume(SESSION, grant.token(), "desktop_act").isEmpty());
            assertEquals(1, calls.get());
        } finally {
            fixture.control.cancel(SESSION);
            running.cancel(true);
        }
    }

    @Test
    void anUnapprovedPlanCannotStartDesktopInput() {
        Fixture fixture = new Fixture();
        AtomicInteger calls = new AtomicInteger();
        fixture.registerAction(calls);
        PermissionContext.force(SESSION, PermissionMode.PLAN, ConfirmPolicy.AUTO, false);

        ToolInvocation result = fixture.pipeline.invoke(ToolRequest.of(SESSION, "desktop_act", CLICK, 0, "one"));

        assertEquals(ToolInvocation.Outcome.POLICY_DENIED, result.outcome());
        assertEquals(0, calls.get());
        assertNull(fixture.permissions.grant.get(), "a blocked plan must not offer a side route through approval");
    }

    @Test
    void cancellingBeforeInvocationNeverCallsTheInputBackend() {
        Fixture fixture = new Fixture();
        AtomicInteger calls = new AtomicInteger();
        fixture.registerAction(calls);
        fixture.control.start(SESSION);
        fixture.control.cancel(SESSION);

        ToolInvocation result = fixture.pipeline.invoke(fullAutoRequest(SESSION, "desktop_act", CLICK, "one"));

        assertEquals(ToolInvocation.Outcome.CONTROL_STOP, result.outcome());
        assertEquals(ToolErrorCode.CANCELLED, result.result().errorCode());
        assertEquals(0, calls.get());
    }

    @Test
    void pausedTaskDoesNotDispatchInputUntilResumed() throws Exception {
        Fixture fixture = new Fixture();
        CountDownLatch actionStarted = new CountDownLatch(1);
        fixture.registry.register("desktop_act", "act", Map.of(), ignored -> {
            actionStarted.countDown();
            return "{\"success\":true}";
        });
        fixture.control.start(SESSION);
        fixture.control.pause(SESSION);
        ToolRequest request = fullAutoRequest(SESSION, "desktop_act", CLICK, "one");
        FutureTask<ToolInvocation> running = start(() -> fixture.pipeline.invoke(request));
        try {
            assertFalse(actionStarted.await(200, TimeUnit.MILLISECONDS));
            assertFalse(running.isDone());
            fixture.control.resume(SESSION);
            assertTrue(running.get(2, TimeUnit.SECONDS).result().isSuccess());
            assertEquals(0, actionStarted.getCount());
        } finally {
            fixture.control.cancel(SESSION);
            running.cancel(true);
        }
    }

    @Test
    void timedOutActionIsUnknownAndCannotBeReplayedEvenAfterALateSuccess() throws Exception {
        Fixture fixture = new Fixture();
        BlockingAction action = new BlockingAction();
        fixture.registry.register("desktop_act", "act", Map.of(), ignored -> action.execute());
        ToolRequest request = fullAutoRequest(SESSION, "desktop_act", CLICK, "one").withExecutionBudget(1);
        FutureTask<ToolInvocation> running = start(() -> fixture.pipeline.invoke(request));
        try {
            assertTrue(action.started.await(1, TimeUnit.SECONDS));
            assertEquals(ToolStatus.UNKNOWN, running.get(3, TimeUnit.SECONDS).result().status());
            assertEquals(ActionExecutionStatus.UNKNOWN, fixture.journal.status("desktop_act"));
            assertEquals(ToolStatus.UNKNOWN, fixture.pipeline.invoke(request).result().status());
            assertEquals(1, action.calls.get(), "unresolved input must not be repeated");

            action.release.countDown();
            assertTrue(action.exited.await(1, TimeUnit.SECONDS));
            assertEquals(ToolStatus.UNKNOWN, fixture.pipeline.invoke(request).result().status());
            assertEquals(ActionExecutionStatus.UNKNOWN, fixture.journal.status("desktop_act"));
            assertEquals(1, action.calls.get());
        } finally {
            action.release.countDown();
            running.cancel(true);
        }
    }

    @Test
    void anotherChatCannotObserveOrActUntilTheTimedOutInputHandlerActuallyExits() throws Exception {
        Fixture fixture = new Fixture();
        BlockingAction action = new BlockingAction();
        CountDownLatch observationStarted = new CountDownLatch(1);
        fixture.registry.register("desktop_act", "act", Map.of(), ignored -> action.execute());
        fixture.registry.register("desktop_observe", "observe", Map.of(), ignored -> {
            observationStarted.countDown();
            return "{\"success\":true}";
        });
        ToolRequest firstRequest = fullAutoRequest(SESSION, "desktop_act", CLICK, "one").withExecutionBudget(1);
        FutureTask<ToolInvocation> first = start(() -> fixture.pipeline.invoke(firstRequest));
        FutureTask<ToolInvocation> second = null;
        try {
            assertTrue(action.started.await(1, TimeUnit.SECONDS));
            assertEquals(ToolStatus.UNKNOWN, first.get(3, TimeUnit.SECONDS).result().status());
            ToolRequest observation = ToolRequest.of("other-chat", "desktop_observe", "{}", 0, "two");
            second = start(() -> fixture.pipeline.invoke(observation));
            assertFalse(observationStarted.await(200, TimeUnit.MILLISECONDS),
                    "different chats and different desktop tools must share the same device lock");
            action.release.countDown();
            assertTrue(second.get(2, TimeUnit.SECONDS).result().isSuccess());
            assertEquals(0, observationStarted.getCount());
        } finally {
            action.release.countDown();
            first.cancel(true);
            if (second != null) second.cancel(true);
        }
    }

    @Test
    void cancellingRunningInputReturnsUnknownWithoutReplayingIt() throws Exception {
        Fixture fixture = new Fixture();
        fixture.control.start(SESSION);
        BlockingAction action = new BlockingAction();
        fixture.registry.register("desktop_act", "act", Map.of(), ignored -> action.execute());
        ToolRequest request = fullAutoRequest(SESSION, "desktop_act", CLICK, "one").withExecutionBudget(20);
        FutureTask<ToolInvocation> running = start(() -> fixture.pipeline.invoke(request));
        try {
            assertTrue(action.started.await(1, TimeUnit.SECONDS));
            fixture.control.cancel(SESSION);
            ToolInvocation result = running.get(2, TimeUnit.SECONDS);
            assertEquals(ToolInvocation.Outcome.CONTROL_STOP, result.outcome());
            assertEquals(ToolStatus.UNKNOWN, result.result().status());
            assertEquals(ToolErrorCode.OUTCOME_UNKNOWN, result.result().errorCode());
            assertEquals(ActionExecutionStatus.UNKNOWN, fixture.journal.status("desktop_act"));
            assertEquals(1, action.calls.get());
        } finally {
            action.release.countDown();
            running.cancel(true);
        }
    }

    @Test
    void approvalCardIdentifiesTheActionWithoutDisclosingTypedText() {
        String display = ToolPipeline.permissionDisplay("desktop_act",
                "{\"observationId\":\"screen-7\",\"action\":\"type\",\"text\":\"秘密123\"}");
        assertTrue(display.contains("screen-7"));
        assertTrue(display.contains("5 个字符"));
        assertFalse(display.contains("秘密123"));
        assertTrue(ToolPipeline.permissionDisplay("desktop_act", CLICK).contains("(20, 40)"));
        assertFalse(ToolPipeline.permissionDisplay("desktop_act", "{broken").isBlank());
    }

    private static ToolRequest fullAutoRequest(String session, String tool, String arguments, String runId) {
        PermissionContext.force(session, PermissionMode.ACCEPT_EDITS, ConfirmPolicy.AUTO, true);
        try {
            return ToolRequest.of(session, tool, arguments, 0, runId);
        } finally {
            PermissionContext.clear();
        }
    }

    private static <T> FutureTask<T> start(java.util.concurrent.Callable<T> operation) {
        FutureTask<T> task = new FutureTask<>(operation);
        Thread.ofVirtual().start(task);
        return task;
    }

    private static final class BlockingAction {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch exited = new CountDownLatch(1);
        final AtomicInteger calls = new AtomicInteger();

        String execute() {
            calls.incrementAndGet();
            started.countDown();
            while (release.getCount() > 0) {
                try { release.await(); }
                catch (InterruptedException ignored) { /* Simulate a native call that has already begun. */ }
            }
            exited.countDown();
            return "{\"success\":true}";
        }
    }

    private static final class Fixture {
        final AtomicReference<Instant> observationExpiry = new AtomicReference<>(Instant.now().plusSeconds(120));
        final ToolRegistry registry = new ToolRegistry();
        final ExecutionControl control = new ExecutionControl(60_000, 20, 10_000);
        final TrackingPermissions permissions = new TrackingPermissions();
        final RecordingJournal journal = new RecordingJournal();
        final ToolPipeline pipeline = new ToolPipeline(control, new ToolHookChain(List.of()), permissions,
                journal, new ToolExecutionGuards(registry, new AgentToolsProperties(4)), registry,
                new ExecPolicyService("allow", "", permissions));

        Fixture() {
            DesktopInteractionService desktop = mock(DesktopInteractionService.class);
            when(desktop.observationExpiresAt(any(), any(), any(), any()))
                    .thenAnswer(i -> Optional.ofNullable(observationExpiry.get()));
            pipeline.setDesktopInteractionService(desktop);
        }

        void registerAction(AtomicInteger calls) {
            registry.register("desktop_act", "act", Map.of(), ignored -> {
                calls.incrementAndGet();
                return "{\"success\":true}";
            });
        }
    }

    private static final class TrackingPermissions extends SessionPermissionStore {
        final AtomicReference<AskGrant> grant = new AtomicReference<>();
        final CountDownLatch attached = new CountDownLatch(1);

        @Override public AskGrant issueAskGrantUntil(String sid, String name, String args, long expiry) {
            AskGrant issued = super.issueAskGrantUntil(sid, name, args, expiry);
            grant.set(issued);
            return issued;
        }

        @Override public boolean attachAskResume(String sid, String token) {
            boolean result = super.attachAskResume(sid, token);
            if (result) attached.countDown();
            return result;
        }
    }

    private static final class RecordingJournal implements ActionJournal {
        final Map<ActionJournalKey, ActionJournalEntry> entries = new ConcurrentHashMap<>();
        public Claim claim(ActionJournalKey key, String tool, String hash, boolean retry) {
            return InMemoryActionJournal.claimEntry(entries, key, tool, hash, retry);
        }
        public Claim claimEffect(ActionJournalKey key, String tool, String hash) {
            return InMemoryActionJournal.claimEffectEntry(entries, key, tool, hash);
        }
        public void append(ActionJournalEntry entry) { entries.put(entry.key(), entry); }
        public Optional<ActionJournalEntry> latest(ActionJournalKey key) {
            return Optional.ofNullable(entries.get(key));
        }
        public List<ActionJournalEntry> unresolved() { return List.of(); }
        ActionExecutionStatus status(String toolName) {
            return entries.values().stream().filter(entry -> entry.toolName().equals(toolName))
                    .findFirst().orElseThrow().status();
        }
    }
}
