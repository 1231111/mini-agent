package com.miniagent.web;

import com.miniagent.agent.planner.*;
import com.miniagent.agent.tool.WorkspaceContext;
import com.miniagent.agent.browser.BrowserService;
import com.miniagent.agent.execution.ToolExecutionGuards;
import com.miniagent.agent.core.AgentLoop;
import com.miniagent.agent.core.ExecutionControl;
import com.miniagent.agent.core.TaskExecutionContext;
import com.miniagent.common.context.OwnerContext;
import com.miniagent.application.ConversationWorkspaceService;
import com.miniagent.application.ConversationTaskProgressService;
import com.miniagent.config.entity.UserRole;
import com.miniagent.config.model.UserModelBinder;
import com.miniagent.config.security.*;
import com.miniagent.config.service.TaskRunService;
import com.miniagent.config.service.TaskRunLeaseRenewer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TaskOutcomeReviewControllerTest {
    final SessionAuthorizationService auth = mock(SessionAuthorizationService.class);
    final PlannerStateStore states = mock(PlannerStateStore.class);
    final UnknownOutcomeReconciler reconciler = mock(UnknownOutcomeReconciler.class);
    final UserModelBinder binder = mock(UserModelBinder.class);
    final TaskRunService runs = mock(TaskRunService.class);
    final ConversationWorkspaceService workspaces = mock(ConversationWorkspaceService.class);
    final ToolExecutionGuards tools = mock(ToolExecutionGuards.class);
    final BrowserService browser = mock(BrowserService.class);
    final ConversationTaskProgressService progress = mock(ConversationTaskProgressService.class);
    final ExecutionControl executionControl = new ExecutionControl(60000, 20, 10000);
    final TaskRunLeaseRenewer leaseRenewer = mock(TaskRunLeaseRenewer.class);
    final TaskOutcomeReviewController controller = new TaskOutcomeReviewController(auth, states, reconciler, binder, runs, workspaces, tools, browser, progress, executionControl, leaseRenewer);
    final TaskRunService.RunHandle handle = new TaskRunService.RunHandle(1L, 7L, "s", "fence");

    MockHttpServletRequest authenticated() {
        var request = new MockHttpServletRequest();
        request.setAttribute(JwtSessionService.ATTR_PRINCIPAL, new AuthenticatedUser(7L, 1L, "test", UserRole.USER));
        return request;
    }
    UnknownOutcomeReconciler.Request body() {
        return new UnknownOutcomeReconciler.Request("exec", 1, 1, "n",
                UnknownOutcomeReconciler.Decision.CONFIRMED_NOT_APPLIED, "核对实际页面，未提交", true);
    }
    StateSnapshot snapshot() {
        return new StateSnapshot(2, "s", "exec", new Goal("g", "核验", "", Map.of(), List.of(), List.of()),
                new TaskGraph(List.of()), Map.of(), Map.of(), List.of(), 0);
    }
    void admit() {
        when(runs.tryStart(7L, "s")).thenReturn(new TaskRunService.StartResult(handle, null));
        when(runs.renewSessionLock("s", "fence")).thenReturn(true);
        when(binder.bindOwner(7L, "1")).thenAnswer(call -> UserModelBinder.Binding.wrap(OwnerContext.bind(new OwnerContext(7L, "1"))));
        when(workspaces.effectiveRoot("s", 7L)).thenReturn(Path.of("D:/isolated-workspace"));
    }
    @AfterEach void cleanup() { WorkspaceContext.clearAll(); OwnerContext.clearCurrentUser(); OwnerContext.clearCurrentTenant(); AgentLoop.clearCurrentSession(); TaskExecutionContext.clear(); }

    @Test void unauthenticatedOrOtherOwnerCannotReadOrResolve() {
        assertFalse(controller.inspect("s", new MockHttpServletRequest()).isSuccess());
        assertFalse(controller.resolve("s", body(), new MockHttpServletRequest()).isSuccess());
        doThrow(new AccessDeniedException("forbidden")).when(auth).requireOwner(7L, "s");
        assertThrows(AccessDeniedException.class, () -> controller.inspect("s", authenticated()));
        assertThrows(AccessDeniedException.class, () -> controller.resolve("s", body(), authenticated()));
        verifyNoInteractions(states, reconciler, runs, binder, workspaces);
    }
    @Test void admissionLockRejectsConcurrentExecutionBeforeAnyStateChange() {
        when(runs.tryStart(7L, "s")).thenReturn(new TaskRunService.StartResult(null, "busy"));
        assertEquals("TASK_BUSY", controller.resolve("s", body(), authenticated()).getCode());
        verifyNoInteractions(reconciler, binder, workspaces);
    }
    @Test void reviewBindsSessionWorkspaceAndRestoresThreadLocalsWithoutDispatchingTools() {
        admit();
        Path original = Path.of("D:/other-workspace");
        WorkspaceContext.setRootOverride(original); WorkspaceContext.setSessionOverride("previous");
        WorkspaceContext.setTaskOverride("old-task");
        WorkspaceContext.setOperationRootSession("previous-root");
        AgentLoop.setCurrentSession("previous-session");
        when(reconciler.reconcile(eq("s"), eq(7L), any(), any())).thenAnswer(call -> {
            assertEquals(Path.of("D:/isolated-workspace"), WorkspaceContext.getRootOverride());
            assertEquals("s", WorkspaceContext.getSessionOverride());
            assertNull(WorkspaceContext.getTaskOverride());
            assertEquals("s", WorkspaceContext.getOperationRootSession());
            assertEquals("s", AgentLoop.getCurrentSession());
            assertEquals("fence", TaskExecutionContext.currentRunToken());
            ((Runnable)call.getArgument(3)).run();
            return snapshot();
        });
        assertTrue(controller.resolve("s", body(), authenticated()).isSuccess());
        assertEquals(original, WorkspaceContext.getRootOverride());
        assertEquals("previous", WorkspaceContext.getSessionOverride());
        assertEquals("old-task", WorkspaceContext.getTaskOverride());
        assertEquals("previous-root", WorkspaceContext.getOperationRootSession());
        assertEquals("previous-session", AgentLoop.getCurrentSession());
        assertNull(TaskExecutionContext.currentRunToken());
        verify(leaseRenewer).start(handle);
        verify(runs).markWaiting(handle, "OUTCOME_REVIEWED");
        verify(progress).persistReviewedOutcome(eq(7L), eq("s"), any(StateSnapshot.class));
        verify(binder, never()).bindModel(any());
    }
    @Test void versionConflictKeepsReviewRejectedAndReleasesAdmission() {
        admit();
        when(reconciler.reconcile(eq("s"), eq(7L), any(), any()))
                .thenThrow(new PlannerStateStore.VersionConflictException(1, 2));
        assertEquals("STATE_CONFLICT", controller.resolve("s", body(), authenticated()).getCode());
        assertNull(WorkspaceContext.getRootOverride());
        verify(runs).markFailed(handle, "OUTCOME_REVIEW_REJECTED");
    }
    @Test void cancellationDuringEvidenceValidationCannotCommitTheReview() {
        admit();
        when(reconciler.reconcile(eq("s"), eq(7L), any(), any())).thenAnswer(call -> {
            executionControl.cancel("s", "fence");
            ((Runnable) call.getArgument(3)).run();
            fail("Cancelled review must not reach commit");
            return snapshot();
        });
        assertFalse(controller.resolve("s", body(), authenticated()).isSuccess());
        verifyNoInteractions(progress);
        verify(runs).markFailed(handle, "OUTCOME_REVIEW_REJECTED");
        assertNull(AgentLoop.getCurrentSession());
        assertNull(TaskExecutionContext.currentRunToken());
    }
    @Test void terminalPersistenceErrorDoesNotReverseCommittedResponse() {
        admit();
        when(reconciler.reconcile(eq("s"), eq(7L), any(), any())).thenReturn(snapshot());
        doThrow(new IllegalStateException("DB unavailable after commit")).when(runs).markWaiting(handle, "OUTCOME_REVIEWED");
        assertTrue(controller.resolve("s", body(), authenticated()).isSuccess());
        verify(runs, never()).markFailed(any(), any());
    }
    @Test void missingExplicitConfirmationCannotAcquireLease() {
        var request = body();
        assertFalse(controller.resolve("s", new UnknownOutcomeReconciler.Request(request.executionId(), 1, 1, "n",
                request.decision(), request.evidence(), false), authenticated()).isSuccess());
        verifyNoInteractions(runs, reconciler, binder);
    }

    @Test void historicalCardFailureCannotRejectAnAlreadyCommittedReview() {
        admit();
        when(reconciler.reconcile(eq("s"), eq(7L), any(), any())).thenReturn(snapshot());
        when(progress.persistReviewedOutcome(eq(7L), eq("s"), any()))
                .thenThrow(new IllegalStateException("projection temporarily unavailable"));
        assertTrue(controller.resolve("s", body(), authenticated()).isSuccess());
        verify(runs).markWaiting(handle, "OUTCOME_REVIEWED");
        verify(runs, never()).markFailed(any(), any());
    }

    @Test void lingeringToolOrBrowserWorkBlocksReviewUntilItActuallyExits() {
        admit();
        when(tools.hasActiveHandlers("s")).thenReturn(true);
        assertFalse(controller.resolve("s", body(), authenticated()).isSuccess());
        verifyNoInteractions(reconciler);
        when(tools.hasActiveHandlers("s")).thenReturn(false);
        when(browser.hasPendingOperations(anyString())).thenReturn(true);
        assertFalse(controller.resolve("s", body(), authenticated()).isSuccess());
        verifyNoInteractions(reconciler);
    }
}
