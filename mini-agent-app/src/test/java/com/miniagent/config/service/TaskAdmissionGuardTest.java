package com.miniagent.config.service;

import com.miniagent.agent.browser.BrowserService;
import com.miniagent.agent.browser.BrowserSessionIdentity;
import com.miniagent.agent.execution.ToolExecutionGuards;
import com.miniagent.agent.tool.WorkspaceContext;
import com.miniagent.common.context.OwnerContext;
import com.miniagent.config.repository.UserRepository;
import com.miniagent.application.ChatStreamingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TaskAdmissionGuardTest {
    @ParameterizedTest
    @ValueSource(strings = {"pending", "running", "completing"})
    void everyLocalStreamLifecyclePhaseBlocksOnlyItsOwnSession(String phase) {
        Fixture f = new Fixture();
        when(f.users.findLocalTenantIdById(7L)).thenReturn(Optional.of(11L));
        ChatStreamingService actualStreams = new ChatStreamingService();
        if (phase.equals("pending")) {
            // Pending is created before eventCenter startup; leave that phase
            // observable by setting the same local bookkeeping as tryCreateStream.
            @SuppressWarnings("unchecked")
            java.util.Set<String> pending = (java.util.Set<String>)
                    org.springframework.test.util.ReflectionTestUtils.getField(actualStreams, "pendingRuns");
            pending.add("s1");
        } else if (phase.equals("running")) actualStreams.markRunning("s1");
        else actualStreams.markCompleting("s1");
        when(f.streams.hasLocalLifecycleReservation(anyString())).thenAnswer(call ->
                actualStreams.hasLocalLifecycleReservation(call.getArgument(0)));

        assertEquals(TaskAdmissionGuard.Decision.BUSY, f.guard.check(7L, "s1"));
        verifyNoInteractions(f.tools, f.browser, f.users);
        assertEquals(TaskAdmissionGuard.Decision.ALLOW, f.guard.check(7L, "s2"));
        actualStreams.markIdle("s1");
        assertEquals(TaskAdmissionGuard.Decision.ALLOW, f.guard.check(7L, "s1"));
    }

    @Test
    void activeOldHandlerBlocksAdmissionWithoutAccessingBrowserOrIdentity() {
        Fixture f = new Fixture();
        when(f.tools.hasActiveHandlers("s1")).thenReturn(true);

        assertEquals(TaskAdmissionGuard.Decision.BUSY, f.guard.check(7L, "s1"));

        verifyNoInteractions(f.users, f.browser);
    }

    @Test
    void pendingBrowserWorkIsScopedToTrustedUserAndSession() {
        Fixture f = new Fixture();
        when(f.users.findLocalTenantIdById(7L)).thenReturn(Optional.of(11L));
        when(f.users.findLocalTenantIdById(8L)).thenReturn(Optional.of(11L));
        String first = trustedKey(7L, "11", "s1");
        String otherUser = trustedKey(8L, "11", "s1");
        String otherSession = trustedKey(7L, "11", "s2");
        assertNotEquals(first, otherUser);
        assertNotEquals(first, otherSession);
        when(f.browser.hasPendingOperations(first)).thenReturn(true);

        assertEquals(TaskAdmissionGuard.Decision.BUSY, f.guard.check(7L, "s1"));
        assertEquals(TaskAdmissionGuard.Decision.ALLOW, f.guard.check(8L, "s1"));
        assertEquals(TaskAdmissionGuard.Decision.ALLOW, f.guard.check(7L, "s2"));

        verify(f.browser).hasPendingOperations(first);
        verify(f.browser).hasPendingOperations(otherUser);
        verify(f.browser).hasPendingOperations(otherSession);
        assertNull(OwnerContext.currentUserId());
        assertNull(WorkspaceContext.getSessionOverride());
    }

    @Test
    void browserStatusFailureRestoresExistingOwnerAndEveryWorkspaceOverride() {
        Fixture f = new Fixture();
        when(f.users.findLocalTenantIdById(7L)).thenReturn(Optional.of(11L));
        when(f.browser.hasPendingOperations(anyString())).thenThrow(new IllegalStateException("unavailable"));
        try (var ignored = OwnerContext.bind(new OwnerContext(7L, "11"))) {
            OwnerContext.setCurrentProjectId("project-before");
            WorkspaceContext.setSessionOverride("outer-session");
            WorkspaceContext.setRootOverride(Path.of("outer-workspace"));
            WorkspaceContext.setTaskOverride("outer-task");
            WorkspaceContext.setOperationRootSession("outer-root-session");

            assertEquals(TaskAdmissionGuard.Decision.UNAVAILABLE, f.guard.check(7L, "s1"));

            assertEquals(7L, OwnerContext.currentUserId());
            assertEquals("11", OwnerContext.currentTenantId());
            assertEquals("project-before", OwnerContext.currentProjectId());
            assertEquals("outer-session", WorkspaceContext.getSessionOverride());
            assertEquals(Path.of("outer-workspace"), WorkspaceContext.getRootOverride());
            assertEquals("outer-task", WorkspaceContext.getTaskOverride());
            assertEquals("outer-root-session", WorkspaceContext.getOperationRootSession());
        } finally { WorkspaceContext.clearAll(); }
    }

    @Test
    void mismatchedExistingIdentityAndUnavailableIdentityFailClosed() {
        Fixture f = new Fixture();
        when(f.users.findLocalTenantIdById(7L)).thenReturn(Optional.of(11L));
        try (var ignored = OwnerContext.bind(new OwnerContext(8L, "11"))) {
            assertEquals(TaskAdmissionGuard.Decision.UNAVAILABLE, f.guard.check(7L, "s1"));
        }
        try (var ignored = OwnerContext.bind(new OwnerContext(7L, "22"))) {
            assertEquals(TaskAdmissionGuard.Decision.UNAVAILABLE, f.guard.check(7L, "s1"));
        }
        when(f.users.findLocalTenantIdById(7L)).thenThrow(new IllegalStateException("database unavailable"));
        assertEquals(TaskAdmissionGuard.Decision.UNAVAILABLE, f.guard.check(7L, "s1"));
        assertEquals(TaskAdmissionGuard.Decision.UNAVAILABLE, f.guard.check(null, "s1"));
        verifyNoInteractions(f.browser);
    }

    private static String trustedKey(Long user, String tenant, String session) {
        String previousSession = WorkspaceContext.getSessionOverride();
        try (var ignored = OwnerContext.bind(new OwnerContext(user, tenant))) {
            WorkspaceContext.setSessionOverride(session);
            return BrowserSessionIdentity.resolve(session);
        } finally { WorkspaceContext.setSessionOverride(previousSession); }
    }

    @SuppressWarnings("unchecked")
    private static final class Fixture {
        final ToolExecutionGuards tools = mock(ToolExecutionGuards.class);
        final BrowserService browser = mock(BrowserService.class);
        final UserRepository users = mock(UserRepository.class);
        final ChatStreamingService streams = mock(ChatStreamingService.class);
        final TaskAdmissionGuard guard;
        Fixture() {
            ObjectProvider<ToolExecutionGuards> toolProvider = mock(ObjectProvider.class);
            ObjectProvider<BrowserService> browserProvider = mock(ObjectProvider.class);
            ObjectProvider<ChatStreamingService> streamProvider = mock(ObjectProvider.class);
            when(toolProvider.getObject()).thenReturn(tools);
            when(browserProvider.getObject()).thenReturn(browser);
            when(streamProvider.getObject()).thenReturn(streams);
            guard = new TaskAdmissionGuard(toolProvider, browserProvider, users, streamProvider);
        }
    }
}
