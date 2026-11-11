package com.miniagent.application;

import com.miniagent.agent.core.ExecutionControl;
import com.miniagent.config.service.TaskRunService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AgentChatCancellationTest {

    @Test
    void cancelOnlySignalsWorkerThatOwnsRunLifecycle() {
        ExecutionControl control = mock(ExecutionControl.class);
        TaskRunService runs = mock(TaskRunService.class);
        when(runs.activeRunToken("s1")).thenReturn(Optional.of("run-A"));
        ChatStreamingService streaming = mock(ChatStreamingService.class);
        AgentChatApplicationService service = new AgentChatApplicationService();
        ReflectionTestUtils.setField(service, "executionControl", control);
        ReflectionTestUtils.setField(service, "taskRunService", runs);
        ReflectionTestUtils.setField(service, "streamingService", streaming);

        service.cancel(7L, "s1");

        verify(control).cancel("s1", "run-A");
        verify(runs).activeRunToken("s1");
        verifyNoInteractions(streaming);
    }

    @Test
    void cancellingIdleSessionDoesNotLeaveSignalsForNextRun() {
        ExecutionControl control = mock(ExecutionControl.class);
        TaskRunService runs = mock(TaskRunService.class);
        when(runs.activeRunToken("idle")).thenReturn(Optional.empty());
        AgentChatApplicationService service = new AgentChatApplicationService();
        ReflectionTestUtils.setField(service, "executionControl", control);
        ReflectionTestUtils.setField(service, "taskRunService", runs);
        service.cancel(7L, "idle");
        verifyNoInteractions(control);
    }
}
