package com.miniagent.config.service;

import com.miniagent.config.entity.AgentTaskRun;
import com.miniagent.config.repository.AgentTaskRunRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TaskRunServiceMetricsTest {
    @Test
    void countsOnlyPersistedStartsAndCommittedTerminalTransition() {
        var registry = new SimpleMeterRegistry();
        var metrics = new TaskRunMetrics(provider(registry));
        AgentTaskRunRepository repository = mock(AgentTaskRunRepository.class);
        PlatformTransactionManager transactions = transactions();
        AgentTaskRun persisted = new AgentTaskRun();
        persisted.setId(7L);
        persisted.setUserId(2L);
        persisted.setSessionId("session");
        persisted.setStatus(AgentTaskRun.Status.RUNNING);
        persisted.setStartedAt(LocalDateTime.now().minusSeconds(3));
        when(repository.findFirstBySessionIdAndStatusOrderByStartedAtDesc(
                "session", AgentTaskRun.Status.RUNNING)).thenReturn(Optional.empty());
        when(repository.save(any(AgentTaskRun.class))).thenAnswer(invocation -> {
            AgentTaskRun row = invocation.getArgument(0);
            row.setId(7L);
            row.setStartedAt(persisted.getStartedAt());
            return row;
        });
        when(repository.findById(7L)).thenReturn(Optional.of(persisted));
        when(repository.finishIfRunning(eq(7L), eq(AgentTaskRun.Status.RUNNING),
                eq(AgentTaskRun.Status.COMPLETED), any(), nullable(String.class)))
                .thenAnswer(invocation -> {
                    persisted.setStatus(AgentTaskRun.Status.COMPLETED);
                    persisted.setFinishedAt(invocation.getArgument(3));
                    return 1;
                });
        TaskRunService service = service(repository, transactions, metrics);

        TaskRunService.StartResult start = service.tryStart(2L, "session");
        assertTrue(start.accepted());
        assertEquals(1.0, registry.get(TaskRunMetrics.STARTED).counter().count());

        service.markCompleted(start.handle());

        assertEquals(1.0, registry.get(TaskRunMetrics.TERMINAL)
                .tag("status", "completed").counter().count());
        assertEquals(1L, registry.get(TaskRunMetrics.DURATION)
                .tag("status", "completed").timer().count());
    }

    @Test
    void staleTerminalCompareAndSetDoesNotIncrementTerminalMetrics() {
        var registry = new SimpleMeterRegistry();
        var metrics = new TaskRunMetrics(provider(registry));
        AgentTaskRunRepository repository = mock(AgentTaskRunRepository.class);
        PlatformTransactionManager transactions = transactions();
        AgentTaskRun persisted = new AgentTaskRun();
        persisted.setId(8L);
        persisted.setUserId(2L);
        persisted.setSessionId("session");
        persisted.setStatus(AgentTaskRun.Status.RUNNING);
        persisted.setStartedAt(LocalDateTime.now());
        when(repository.findFirstBySessionIdAndStatusOrderByStartedAtDesc(
                "session", AgentTaskRun.Status.RUNNING)).thenReturn(Optional.empty());
        when(repository.save(any(AgentTaskRun.class))).thenAnswer(invocation -> {
            AgentTaskRun row = invocation.getArgument(0);
            row.setId(8L);
            return row;
        });
        when(repository.findById(8L)).thenReturn(Optional.of(persisted));
        when(repository.finishIfRunning(eq(8L), eq(AgentTaskRun.Status.RUNNING),
                eq(AgentTaskRun.Status.COMPLETED), any(), nullable(String.class))).thenReturn(0);
        TaskRunService service = service(repository, transactions, metrics);

        TaskRunService.StartResult start = service.tryStart(2L, "session");
        assertTrue(start.accepted());
        service.markCompleted(start.handle());

        assertEquals(1.0, registry.get(TaskRunMetrics.STARTED).counter().count());
        assertEquals(0.0, registry.get(TaskRunMetrics.TERMINAL)
                .tag("status", "completed").counter().count());
    }

    private static TaskRunService service(AgentTaskRunRepository repository,
                                          PlatformTransactionManager transactions,
                                          TaskRunMetrics metrics) {
        TaskRunService service = new TaskRunService();
        ReflectionTestUtils.setField(service, "repository", repository);
        ReflectionTestUtils.setField(service, "transactionManager", transactions);
        ReflectionTestUtils.setField(service, "taskRunMetrics", metrics);
        return service;
    }

    private static ObjectProvider<io.micrometer.core.instrument.MeterRegistry> provider(
            io.micrometer.core.instrument.MeterRegistry registry) {
        ObjectProvider<io.micrometer.core.instrument.MeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(registry);
        return provider;
    }

    private static PlatformTransactionManager transactions() {
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any(TransactionDefinition.class))).thenReturn(new SimpleTransactionStatus());
        return manager;
    }
}
