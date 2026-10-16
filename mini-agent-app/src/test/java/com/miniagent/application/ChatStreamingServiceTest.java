package com.miniagent.application;

import com.miniagent.agent.core.ExecutionControl;
import com.miniagent.agent.core.SessionEventCenter;
import com.miniagent.config.service.TaskRunService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatStreamingServiceTest {

    private ChatStreamingService service;
    private SessionEventCenter eventCenter;
    private ExecutionControl executionControl;

    @BeforeEach
    void setUp() {
        eventCenter = mock(SessionEventCenter.class);
        executionControl = mock(ExecutionControl.class);
        service = new ChatStreamingService();
        ReflectionTestUtils.setField(service, "eventCenter", eventCenter);
        ReflectionTestUtils.setField(service, "taskRunService", mock(TaskRunService.class));
        ReflectionTestUtils.setField(service, "executionControl", executionControl);
        ReflectionTestUtils.setField(service, "sseTimeoutMs", 45_000L);
        when(eventCenter.attachClient(anyString(), anyLong(), any(SseEmitter.class))).thenReturn(true);
    }

    @Test
    void disconnectBeforeWorkerMarksRunningIsDeliveredAfterLeaseStarts() {
        SseEmitter emitter = service.createStream("s1", "hello", 7L);

        // Simulate a browser closing the connection before the async worker gets
        // scheduled. The cancellation must be deferred until markRunning(),
        // because ExecutionControl.start() replaces any fallback lease.
        runCompletionCallback(emitter);
        service.markRunning("s1");

        verify(executionControl).cancel("s1");
        assertThat(emitter.getTimeout()).isEqualTo(45_000L);
    }

    @Test
    void normalCompletionAfterMarkCompletingDoesNotCancelAFinishedRun() {
        SseEmitter emitter = service.createStream("s1", "hello", 7L);
        service.markRunning("s1");
        service.markCompleting("s1");

        runCompletionCallback(emitter);

        verify(executionControl, never()).cancel(anyString());
    }

    @Test
    void terminalizingRunStillReservesSessionUntilCleanup() {
        SseEmitter first = service.createStream("s1", "hello", 7L);
        service.markRunning("s1");
        service.markCompleting("s1");

        SseEmitter second = service.createStream("s1", "again", 7L);

        assertThat(second.getTimeout()).isZero();
        verify(eventCenter).hasRunningSession("s1");
        service.markIdle("s1");
        // Keep the first emitter referenced so this test documents the lifecycle
        // reservation rather than relying on a GC side effect.
        assertThat(first).isNotNull();
    }

    @Test
    void repeatedEmitterCallbacksIssueOnlyOneCancellation() {
        SseEmitter emitter = service.createStream("s1", "hello", 7L);
        service.markRunning("s1");

        runCompletionCallback(emitter);
        runCompletionCallback(emitter);

        verify(executionControl).cancel("s1");
    }

    private static void runCompletionCallback(SseEmitter emitter) {
        Object callback = ReflectionTestUtils.getField(emitter, "completionCallback");
        ReflectionTestUtils.invokeMethod(callback, "run");
    }
}
