package com.miniagent.agent.trace;

import com.miniagent.config.entity.AgentTraceStep;
import com.miniagent.config.repository.AgentTraceStepRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TraceMetricsTest {

    @Test
    void recordsLlmLatencyTokensAndCompressionWithoutUserIdentifiers() {
        var registry = new SimpleMeterRegistry();
        var repository = mock(AgentTraceStepRepository.class);
        when(repository.save(any(AgentTraceStep.class))).thenAnswer(invocation -> invocation.getArgument(0));
        var recorder = new TraceRecorder();
        ReflectionTestUtils.setField(recorder, "repo", repository);
        ReflectionTestUtils.setField(recorder, "meterRegistry", registry);

        recorder.recordLlmLatency("session-secret", 2, 125, 17, 9);
        recorder.recordCompression("session-secret", 3, 12, 7, 900, 510);

        assertThat(registry.get("miniagent.llm.request.duration").timer().count()).isEqualTo(1);
        assertThat(registry.get("miniagent.llm.tokens").tag("direction", "input").counter().count()).isEqualTo(17);
        assertThat(registry.get("miniagent.llm.tokens").tag("direction", "output").counter().count()).isEqualTo(9);
        assertThat(registry.get("miniagent.context.compression.tokens_saved").counter().count()).isEqualTo(390);
        assertThat(registry.getMeters()).allSatisfy(meter -> assertThat(meter.getId().getTags())
                .extracting("value").doesNotContain("session-secret"));
    }

    @Test
    void zeroUsageDoesNotAttemptAnInvalidCounterIncrement() {
        var registry = new SimpleMeterRegistry();
        var repository = mock(AgentTraceStepRepository.class);
        when(repository.save(any(AgentTraceStep.class))).thenAnswer(invocation -> invocation.getArgument(0));
        var recorder = new TraceRecorder();
        ReflectionTestUtils.setField(recorder, "repo", repository);
        ReflectionTestUtils.setField(recorder, "meterRegistry", registry);

        recorder.recordLlmLatency("session-secret", 1, 0, 0, 0);

        assertThat(registry.get("miniagent.llm.request.duration").timer().count()).isEqualTo(1);
        assertThat(registry.getMeters()).noneMatch(meter -> meter.getId().getName().equals("miniagent.llm.tokens"));
    }
}
