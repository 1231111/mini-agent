package com.miniagent.agent.core;

import com.miniagent.agent.trace.TraceRecorder;
import com.miniagent.config.entity.AgentTraceStep;
import com.miniagent.config.repository.AgentTraceStepRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RunScopeTracePropagationTest {
    @Test void traceIdentitySurvivesExplicitWorkerThreadBinding() throws Exception {
        AgentTraceStepRepository repository = mock(AgentTraceStepRepository.class);
        when(repository.save(any(AgentTraceStep.class))).thenAnswer(call -> call.getArgument(0));
        TraceRecorder recorder = new TraceRecorder();
        ReflectionTestUtils.setField(recorder, "repo", repository);
        recorder.beginExecution("trace-session", "exec-trace-1", "sensitive question");
        RunScope scope = RunScope.capture();

        Thread worker = new Thread(() -> {
            try (RunScope.Binding ignored = scope.bind()) {
                recorder.recordNode("trace-session", 1, "PERM_GRANT", "grant", "SUCCESS", 0);
            }
        });
        worker.start();
        worker.join();
        recorder.endExecution("SUCCESS");

        ArgumentCaptor<AgentTraceStep> saved = ArgumentCaptor.forClass(AgentTraceStep.class);
        verify(repository, atLeast(3)).save(saved.capture());
        assertTrue(saved.getAllValues().stream().anyMatch(step -> "PERM_GRANT".equals(step.getStepType())
                && "exec-trace-1".equals(step.getExecutionId())
                && "sensitive question".equals(step.getUserQuestion())));
    }
}
