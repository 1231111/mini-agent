package com.miniagent.config.service;

import com.miniagent.config.entity.AgentTaskRun;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TaskRunMetricsTest {

    @Test
    void publishesBoundedLifecycleCountersAndOutcomeDurationWithoutIdentityLabels() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ObjectProvider<io.micrometer.core.instrument.MeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(registry);
        TaskRunMetrics metrics = new TaskRunMetrics(provider);
        LocalDateTime start = LocalDateTime.parse("2026-11-20T10:00:00");
        LocalDateTime finish = start.plusSeconds(12);

        metrics.started();
        metrics.terminal(AgentTaskRun.Status.COMPLETED, start, finish);
        metrics.terminal(AgentTaskRun.Status.WAITING, start, finish);
        metrics.terminal(AgentTaskRun.Status.FAILED, start, finish);

        assertEquals(1.0, registry.get(TaskRunMetrics.STARTED).counter().count());
        assertEquals(1.0, registry.get(TaskRunMetrics.TERMINAL).tag("status", "completed").counter().count());
        assertEquals(1.0, registry.get(TaskRunMetrics.TERMINAL).tag("status", "waiting").counter().count());
        assertEquals(1.0, registry.get(TaskRunMetrics.TERMINAL).tag("status", "failed").counter().count());
        assertEquals(12.0, registry.get(TaskRunMetrics.DURATION).tag("status", "completed")
                .timer().totalTime(java.util.concurrent.TimeUnit.SECONDS));
        assertFalse(registry.get(TaskRunMetrics.TERMINAL).counter().getId().getTags().stream()
                .anyMatch(tag -> !"status".equals(tag.getKey())));
        assertNull(registry.find(TaskRunMetrics.TERMINAL).tag("status", "running").counter(),
                "RUNNING is an active gauge, never a terminal outcome counter");
    }

    @Test
    void toleratesUnavailableMetricsRegistryAndMissingTimestamps() {
        ObjectProvider<io.micrometer.core.instrument.MeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        TaskRunMetrics metrics = new TaskRunMetrics(provider);

        metrics.started();
        metrics.terminal(AgentTaskRun.Status.INTERRUPTED, null, null);
        metrics.terminal(AgentTaskRun.Status.RUNNING, null, null);
    }
}
