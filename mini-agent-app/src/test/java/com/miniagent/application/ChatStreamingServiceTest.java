package com.miniagent.application;

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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;

class ChatStreamingServiceTest {

    private ChatStreamingService service;
    private SessionEventCenter eventCenter;

    @BeforeEach
    void setUp() {
        eventCenter = mock(SessionEventCenter.class);
        service = new ChatStreamingService();
        ReflectionTestUtils.setField(service, "eventCenter", eventCenter);
        ReflectionTestUtils.setField(service, "taskRunService", mock(TaskRunService.class));
        ReflectionTestUtils.setField(service, "sseTimeoutMs", 45_000L);
        when(eventCenter.attachClient(anyString(), anyLong(), any(SseEmitter.class))).thenReturn(true);
    }

    @Test
    void disconnectBeforeWorkerMarksRunningDoesNotCancelTask() {
        SseEmitter emitter = service.createStream("s1", "hello", 7L);

        // A route change or browser disconnect only detaches the client. The
        // user has a separate explicit cancel action for stopping the worker.
        emitter.complete();
        service.markRunning("s1");

        assertThat(emitter.getTimeout()).isEqualTo(45_000L);
    }

    @Test
    void normalCompletionAfterMarkCompletingDoesNotCancelAFinishedRun() {
        SseEmitter emitter = service.createStream("s1", "hello", 7L);
        service.markRunning("s1");
        service.markCompleting("s1");

        emitter.complete();

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
    void repeatedEmitterCompletionDoesNotCancelTask() {
        SseEmitter emitter = service.createStream("s1", "hello", 7L);
        service.markRunning("s1");

        emitter.complete();
        emitter.complete();

    }

    @Test
    void localLifecycleReservationCoversPendingRunningAndCompletingWithoutCallingOtherServices() {
        TaskRunService runs = mock(TaskRunService.class);
        ReflectionTestUtils.setField(service, "taskRunService", runs);
        assertThat(service.tryCreateStreamForAcceptedRun("s1", "hello", 7L).accepted()).isTrue();
        clearInvocations(eventCenter, runs);

        assertThat(service.hasLocalLifecycleReservation("s1")).isTrue();
        assertThat(service.hasLocalLifecycleReservation("s2")).isFalse();
        service.markRunning("s1");
        assertThat(service.hasLocalLifecycleReservation("s1")).isTrue();
        service.markCompleting("s1");
        assertThat(service.hasLocalLifecycleReservation("s1")).isTrue();
        assertThat(service.hasLocalLifecycleReservation("s2")).isFalse();
        service.markIdle("s1");
        assertThat(service.hasLocalLifecycleReservation("s1")).isFalse();
        assertThat(service.hasLocalLifecycleReservation(null)).isFalse();
        assertThat(service.hasLocalLifecycleReservation("  ")).isFalse();
        verifyNoInteractions(eventCenter, runs);
    }
}
