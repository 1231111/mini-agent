package com.miniagent.agent.trace;

import com.miniagent.config.repository.AgentTraceStepRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

class TraceRetentionServiceTest {

    @Test
    void deletesOnlyRowsOlderThanConfiguredRetention() {
        AgentTraceStepRepository repository = mock(AgentTraceStepRepository.class);
        when(repository.deleteByCreatedAtBefore(any(LocalDateTime.class))).thenReturn(12L);
        TraceRetentionService service = new TraceRetentionService(repository);
        ReflectionTestUtils.setField(service, "retentionDays", 14);

        service.purgeExpired();

        verify(repository).deleteByCreatedAtBefore(argThat(cutoff ->
                cutoff.isAfter(LocalDateTime.now().minusDays(15))
                        && cutoff.isBefore(LocalDateTime.now().minusDays(13))));
        assertEquals(14, service.effectiveRetentionDays());
    }

    @Test
    void invalidRetentionFallsBackToOneDayAndDoesNotEscapeServingPath() {
        AgentTraceStepRepository repository = mock(AgentTraceStepRepository.class);
        when(repository.deleteByCreatedAtBefore(any(LocalDateTime.class)))
                .thenThrow(new RuntimeException("database unavailable"));
        TraceRetentionService service = new TraceRetentionService(repository);
        ReflectionTestUtils.setField(service, "retentionDays", 0);

        service.purgeExpired();

        assertEquals(1, service.effectiveRetentionDays());
        verify(repository).deleteByCreatedAtBefore(any(LocalDateTime.class));
    }
}
