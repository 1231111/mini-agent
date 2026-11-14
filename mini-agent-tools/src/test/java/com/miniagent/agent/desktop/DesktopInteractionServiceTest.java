package com.miniagent.agent.desktop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.agent.tool.ToolErrorCode;
import com.miniagent.agent.tool.ToolStatus;
import com.miniagent.agent.tool.WorkspaceContext;
import com.miniagent.common.context.OwnerContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.time.*;
import java.util.List;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DesktopInteractionServiceTest {
    @TempDir Path root;
    private DesktopBackend backend;
    private DesktopInteractionService service;
    private final DesktopBackend.Window window = new DesktopBackend.Window("a", 99, "Fixture", 20, 30, 200, 100);
    @BeforeEach void setup() {
        backend = mock(DesktopBackend.class);
        when(backend.available()).thenReturn(true);
        when(backend.foreground()).thenReturn(window);
        when(backend.windows()).thenReturn(List.of(window));
        when(backend.capture(any())).thenAnswer(i -> new DesktopBackend.Capture(i.getArgument(0),
                new BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB)));
        service = new DesktopInteractionService(backend, Clock.fixed(Instant.parse("2026-11-12T04:00:00Z"), ZoneOffset.UTC), true);
        bind("chat-a");
    }
    private void bind(String session) {
        OwnerContext.setCurrentUser(7L); OwnerContext.setCurrentTenant("tenant");
        WorkspaceContext.setSessionOverride(session); WorkspaceContext.setRootOverride(root);
    }
    @AfterEach void cleanup() {
        service.close(); OwnerContext.clearCurrentUser(); OwnerContext.clearCurrentTenant();
        WorkspaceContext.clearSessionOverride(); WorkspaceContext.clearRootOverride(); Thread.interrupted();
    }
    private String observe() throws Exception {
        var result = service.observe(); assertTrue(result.isSuccess(), result.legacyText());
        var json = new ObjectMapper().readTree(result.legacyText());
        Path image = Path.of(json.path("tool_images").get(0).path("path").asText());
        assertTrue(image.startsWith(root.toRealPath())); assertNotNull(javax.imageio.ImageIO.read(image.toFile()));
        return json.path("observationId").asText();
    }
    private static DesktopBackend.Action click(int x, int y) { return new DesktopBackend.Action("click", x, y, null, null, null, null); }
    @Test void approvalExpiryIsOwnedAndDoesNotExtendOrConsumeTheObservation() throws Exception {
        String id = observe();
        Instant expiry = Instant.parse("2026-11-12T04:02:00Z");
        assertEquals(expiry, service.observationExpiresAt(7L, "tenant", "chat-a", id).orElseThrow());
        assertTrue(service.observationExpiresAt(8L, "tenant", "chat-a", id).isEmpty());
        assertTrue(service.observationExpiresAt(7L, "other", "chat-a", id).isEmpty());
        assertTrue(service.observationExpiresAt(7L, "tenant", "chat-b", id).isEmpty());
        assertTrue(service.observationExpiresAt(7L, "tenant", "chat-a", "old-id").isEmpty());
        assertTrue(service.observationExpiresAt(null, "tenant", "chat-a", id).isEmpty());
        verify(backend, never()).execute(any(), any());
        assertTrue(service.act(id, click(1, 1)).isSuccess(), "reading approval metadata must not consume the observation");
        assertTrue(service.observationExpiresAt(7L, "tenant", "chat-a", id).isEmpty());
    }
    @Test void expiredAndRevokedObservationsCannotIssueApprovalMetadata() throws Exception {
        Clock clock = mock(Clock.class);
        var now = new java.util.concurrent.atomic.AtomicReference<>(Instant.parse("2026-11-12T04:00:00Z"));
        when(clock.instant()).thenAnswer(i -> now.get());
        service = new DesktopInteractionService(backend, clock, true);
        String id = observe();
        now.set(now.get().plusSeconds(120));
        assertTrue(service.observationExpiresAt(7L, "tenant", "chat-a", id).isEmpty());
        assertEquals(ToolErrorCode.CONFLICT, service.act(id, click(1, 1)).errorCode());
        String fresh = observe();
        service.revokeSession("chat-a");
        assertTrue(service.observationExpiresAt(7L, "tenant", "chat-a", fresh).isEmpty());
        verify(backend, never()).execute(any(), any());
    }
    @Test void approvalMetadataDoesNotBlockBehindAnInFlightNativeAction() throws Exception {
        String id = observe();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        doAnswer(i -> { entered.countDown(); assertTrue(release.await(3, TimeUnit.SECONDS)); return null; })
                .when(backend).execute(any(), any());
        try (var pool = Executors.newSingleThreadExecutor()) {
            var action = pool.submit(() -> {
                bind("chat-a");
                try { return service.act(id, click(1, 1)); }
                finally {
                    OwnerContext.clearCurrentUser(); OwnerContext.clearCurrentTenant();
                    WorkspaceContext.clearSessionOverride(); WorkspaceContext.clearRootOverride();
                }
            });
            try {
                assertTrue(entered.await(1, TimeUnit.SECONDS));
                assertTimeoutPreemptively(Duration.ofMillis(300), () ->
                        assertTrue(service.observationExpiresAt(7L, "tenant", "chat-a", id).isEmpty()));
            } finally { release.countDown(); }
            assertTrue(action.get(2, TimeUnit.SECONDS).isSuccess());
        }
    }
    @Test void observesActsAndReturnsNewObservationWithoutReplayingOldInput() throws Exception {
        String id = observe();
        var result = service.act(id, click(10, 20)); assertTrue(result.isSuccess(), result.legacyText());
        var json = new ObjectMapper().readTree(result.legacyText());
        assertTrue(json.path("verificationRequired").asBoolean()); assertNotEquals(id, json.path("observationId").asText());
        assertEquals(ToolErrorCode.CONFLICT, service.act(id, click(10, 20)).errorCode());
        verify(backend, times(1)).execute(eq(window), any());
    }
    @Test void otherChatCannotObserveActOrReleaseAnOwnedDevice() throws Exception {
        String id = observe(); bind("chat-b");
        assertEquals(ToolErrorCode.RESOURCE_BUSY, service.observe().errorCode());
        assertEquals(ToolErrorCode.CONFLICT, service.act(id, click(1, 2)).errorCode());
        service.release(); verify(backend, never()).releaseInputs();
        bind("chat-a"); assertTrue(service.act(id, click(1, 2)).isSuccess());
    }
    @Test void rejectsOutOfBoundsAndInvalidActionBeforeAnyNativeInput() throws Exception {
        String id = observe();
        assertEquals(ToolErrorCode.INVALID_ARGUMENT, service.act(id, click(200, 0)).errorCode());
        assertEquals(ToolErrorCode.INVALID_ARGUMENT, service.act(id, new DesktopBackend.Action("key", null, null, null, "CTRL+UNKNOWN", null, null)).errorCode());
        verify(backend, never()).activate(any()); verify(backend, never()).execute(any(), any());
    }
    @Test void focusOnlyAcceptsAWindowListedInTheObservation() throws Exception {
        String id = observe();
        assertEquals(ToolErrorCode.INVALID_ARGUMENT, service.act(id,
                new DesktopBackend.Action("focus", null, null, null, null, null, "unlisted")).errorCode());
        verify(backend, never()).activate(any());
    }
    @Test void exceptionsAfterInputStartAreUnknownAndObservationIsConsumed() throws Exception {
        String id = observe(); doThrow(new IllegalStateException("lost acknowledgement")).when(backend).execute(any(), any());
        assertEquals(ToolStatus.UNKNOWN, service.act(id, click(1, 1)).status());
        assertEquals(ToolErrorCode.CONFLICT, service.act(id, click(1, 1)).errorCode());
        verify(backend).releaseInputs();
    }
    @Test void explicitReleaseAndPauseInvalidateTheObservation() throws Exception {
        String first = observe(); service.release();
        assertEquals(ToolErrorCode.CONFLICT, service.act(first, click(1, 1)).errorCode());
        String second = observe(); service.revokeSession("chat-a");
        assertEquals(ToolErrorCode.CONFLICT, service.act(second, click(1, 1)).errorCode());
    }
    @Test void requiresTrustedOwnerAndWorkspace() {
        OwnerContext.clearCurrentUser(); assertFalse(service.observe().isSuccess());
        verify(backend, never()).capture(any());
    }
    @Test void disabledAndUnsupportedBackendsReturnActionableErrors() {
        var disabled = new DesktopInteractionService(backend, Clock.systemUTC(), false);
        assertEquals(ToolErrorCode.PERMISSION_DENIED, disabled.observe().errorCode());
        when(backend.available()).thenReturn(false); when(backend.unavailableReason()).thenReturn("unsupported platform");
        assertEquals(ToolErrorCode.DEPENDENCY_UNAVAILABLE, service.observe().errorCode());
    }
    @Test void pauseInterruptsActiveInputAndReleasesBeforeReturning() throws Exception {
        String id = observe(); CountDownLatch entered = new CountDownLatch(1);
        doAnswer(i -> { entered.countDown(); Thread.sleep(10_000); return null; }).when(backend).execute(any(), any());
        try (var pool = Executors.newSingleThreadExecutor()) {
            var result = pool.submit(() -> { bind("chat-a"); try { return service.act(id, click(1, 1)); }
                finally { Thread.interrupted(); OwnerContext.clearCurrentUser(); OwnerContext.clearCurrentTenant();
                    WorkspaceContext.clearSessionOverride(); WorkspaceContext.clearRootOverride(); } });
            assertTrue(entered.await(2, TimeUnit.SECONDS)); service.revokeSession("chat-a");
            assertEquals(ToolStatus.UNKNOWN, result.get(2, TimeUnit.SECONDS).status());
            assertEquals(ToolErrorCode.CONFLICT, service.act(id, click(1, 1)).errorCode());
        }
    }
    @Test void pauseBeforeNativeDispatchCannotMissTheActionThreadRegistration() throws Exception {
        String id = observe();
        CountDownLatch actEnteredAvailabilityCheck = new CountDownLatch(1);
        CountDownLatch allowAvailabilityCheckToReturn = new CountDownLatch(1);
        when(backend.available()).thenAnswer(i -> {
            actEnteredAvailabilityCheck.countDown();
            assertTrue(allowAvailabilityCheckToReturn.await(3, TimeUnit.SECONDS));
            return true;
        });
        Thread revoker = new Thread(() -> service.revokeSession("chat-a"), "desktop-pause-before-dispatch");
        try (var pool = Executors.newSingleThreadExecutor()) {
            var result = pool.submit(() -> {
                bind("chat-a");
                try { return service.act(id, click(1, 1)); }
                finally {
                    Thread.interrupted(); OwnerContext.clearCurrentUser(); OwnerContext.clearCurrentTenant();
                    WorkspaceContext.clearSessionOverride(); WorkspaceContext.clearRootOverride();
                }
            });
            assertTrue(actEnteredAvailabilityCheck.await(2, TimeUnit.SECONDS));
            revoker.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (revoker.getState() != Thread.State.BLOCKED && revoker.isAlive() && System.nanoTime() < deadline)
                Thread.sleep(1);
            assertEquals(Thread.State.BLOCKED, revoker.getState(), "pause must reach the held service monitor before dispatch resumes");
            allowAvailabilityCheckToReturn.countDown();
            var paused = result.get(2, TimeUnit.SECONDS);
            assertEquals(ToolErrorCode.CANCELLED, paused.errorCode(), paused.legacyText());
            verify(backend, never()).activate(any());
            verify(backend, never()).execute(any(), any());
            revoker.join(2_000);
            assertFalse(revoker.isAlive());
        } finally {
            allowAvailabilityCheckToReturn.countDown();
            revoker.join(2_000);
        }
    }
    @Test void failureToReleaseNativeInputsCannotReturnASuccessfulAction() throws Exception {
        String id = observe();
        doThrow(new IllegalStateException("input release was not acknowledged")).doNothing().when(backend).releaseInputs();
        var result = service.act(id, click(1, 1));
        assertEquals(ToolStatus.UNKNOWN, result.status(), result.legacyText());
        assertEquals(ToolErrorCode.CONFLICT, service.act(id, click(1, 1)).errorCode());
    }
    @Test void keyAndScrollValidationHasBoundedInputs() {
        assertThrows(IllegalArgumentException.class, () -> DesktopInteractionService.validate(
                new DesktopBackend.Action("scroll", null, null, null, null, Integer.MIN_VALUE, null), window));
        assertThrows(IllegalArgumentException.class, () -> DesktopInteractionService.validate(
                new DesktopBackend.Action("type", null, null, "x".repeat(2049), null, null, null), window));
        assertEquals(0x0D, WindowsDesktopBackend.keyCode("ENTER"));
    }
}
