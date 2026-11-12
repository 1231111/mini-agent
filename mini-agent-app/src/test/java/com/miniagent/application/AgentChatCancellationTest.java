package com.miniagent.application;

import com.miniagent.agent.core.ExecutionControl;
import com.miniagent.agent.desktop.DesktopInteractionService;
import com.miniagent.config.service.TaskRunService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AgentChatCancellationTest {

    @Test
    void cancelOnlySignalsWorkerThatOwnsRunLifecycle() {
        ExecutionControl control = mock(ExecutionControl.class);
        DesktopInteractionService desktop = mock(DesktopInteractionService.class);
        TaskRunService runs = mock(TaskRunService.class);
        when(runs.activeRunToken("s1")).thenReturn(Optional.of("run-A"));
        ChatStreamingService streaming = mock(ChatStreamingService.class);
        AgentChatApplicationService service = new AgentChatApplicationService();
        ReflectionTestUtils.setField(service, "executionControl", control);
        ReflectionTestUtils.setField(service, "taskRunService", runs);
        ReflectionTestUtils.setField(service, "streamingService", streaming);
        ReflectionTestUtils.setField(service, "desktopInteractionService", desktop);

        service.cancel(7L, "s1");

        var order = inOrder(control, desktop);
        order.verify(control).cancel("s1", "run-A");
        order.verify(desktop).revokeSession("s1");
        verify(runs).activeRunToken("s1");
        verifyNoInteractions(streaming);
    }

    @Test
    void cancellingIdleSessionDoesNotLeaveSignalsForNextRun() {
        ExecutionControl control = mock(ExecutionControl.class);
        DesktopInteractionService desktop = mock(DesktopInteractionService.class);
        TaskRunService runs = mock(TaskRunService.class);
        when(runs.activeRunToken("idle")).thenReturn(Optional.empty());
        AgentChatApplicationService service = new AgentChatApplicationService();
        ReflectionTestUtils.setField(service, "executionControl", control);
        ReflectionTestUtils.setField(service, "taskRunService", runs);
        ReflectionTestUtils.setField(service, "desktopInteractionService", desktop);
        service.cancel(7L, "idle");
        verifyNoInteractions(control);
        verify(desktop).revokeSession("idle");
    }

    @Test
    void pauseSignalsTheActiveRunAndRevokesDesktopInputBeforeReturning() {
        ExecutionControl control = mock(ExecutionControl.class);
        DesktopInteractionService desktop = mock(DesktopInteractionService.class);
        TaskRunService runs = mock(TaskRunService.class);
        when(runs.activeRunToken("s1")).thenReturn(Optional.of("run-A"));
        ChatStreamingService streaming = mock(ChatStreamingService.class);
        AgentChatApplicationService service = new AgentChatApplicationService();
        ReflectionTestUtils.setField(service, "executionControl", control);
        ReflectionTestUtils.setField(service, "taskRunService", runs);
        ReflectionTestUtils.setField(service, "streamingService", streaming);
        ReflectionTestUtils.setField(service, "desktopInteractionService", desktop);

        service.pause("s1");

        var order = inOrder(control, desktop);
        order.verify(control).pause("s1", "run-A");
        order.verify(desktop).revokeSession("s1");
        verifyNoInteractions(streaming);
    }

    @Test
    void pausingIdleSessionStillRevokesAnyRemainingDesktopLease() {
        ExecutionControl control = mock(ExecutionControl.class);
        DesktopInteractionService desktop = mock(DesktopInteractionService.class);
        TaskRunService runs = mock(TaskRunService.class);
        when(runs.activeRunToken("idle")).thenReturn(Optional.empty());
        AgentChatApplicationService service = new AgentChatApplicationService();
        ReflectionTestUtils.setField(service, "executionControl", control);
        ReflectionTestUtils.setField(service, "taskRunService", runs);
        ReflectionTestUtils.setField(service, "desktopInteractionService", desktop);

        service.pause("idle");

        verifyNoInteractions(control);
        verify(desktop).revokeSession("idle");
    }

    @ParameterizedTest
    @ValueSource(strings = {"pause", "cancel"})
    void failedRunTokenReadStillRevokesDesktopInputAndPreservesTheStoreFailure(String operation) {
        ExecutionControl control = mock(ExecutionControl.class);
        DesktopInteractionService desktop = mock(DesktopInteractionService.class);
        TaskRunService runs = mock(TaskRunService.class);
        IllegalStateException storeFailure = new IllegalStateException("redis run-token read unavailable");
        when(runs.activeRunToken("s1")).thenThrow(storeFailure);
        AgentChatApplicationService service = new AgentChatApplicationService();
        ReflectionTestUtils.setField(service, "executionControl", control);
        ReflectionTestUtils.setField(service, "taskRunService", runs);
        ReflectionTestUtils.setField(service, "desktopInteractionService", desktop);

        assertSame(storeFailure, assertThrows(IllegalStateException.class,
                () -> requestControl(service, operation)));

        verify(desktop).revokeSession("s1");
        verifyNoInteractions(control);
    }

    @ParameterizedTest
    @ValueSource(strings = {"pause", "cancel"})
    void failedControlSignalWriteStillRevokesDesktopInputAndPreservesTheStoreFailure(String operation) {
        ExecutionControl control = mock(ExecutionControl.class);
        DesktopInteractionService desktop = mock(DesktopInteractionService.class);
        TaskRunService runs = mock(TaskRunService.class);
        when(runs.activeRunToken("s1")).thenReturn(Optional.of("run-A"));
        IllegalStateException storeFailure = new IllegalStateException("redis control signal write unavailable");
        if ("pause".equals(operation)) {
            doThrow(storeFailure).when(control).pause("s1", "run-A");
        } else {
            doThrow(storeFailure).when(control).cancel("s1", "run-A");
        }
        AgentChatApplicationService service = new AgentChatApplicationService();
        ReflectionTestUtils.setField(service, "executionControl", control);
        ReflectionTestUtils.setField(service, "taskRunService", runs);
        ReflectionTestUtils.setField(service, "desktopInteractionService", desktop);

        assertSame(storeFailure, assertThrows(IllegalStateException.class,
                () -> requestControl(service, operation)));

        var order = inOrder(control, desktop);
        if ("pause".equals(operation)) {
            order.verify(control).pause("s1", "run-A");
        } else {
            order.verify(control).cancel("s1", "run-A");
        }
        order.verify(desktop).revokeSession("s1");
    }

    private static void requestControl(AgentChatApplicationService service, String operation) {
        if ("pause".equals(operation)) service.pause("s1");
        else service.cancel(7L, "s1");
    }
}
