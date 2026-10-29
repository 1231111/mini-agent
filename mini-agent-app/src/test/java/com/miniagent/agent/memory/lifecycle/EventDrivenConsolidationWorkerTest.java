package com.miniagent.agent.memory.lifecycle;

import com.miniagent.agent.memory.MemoryTaskLock;
import com.miniagent.agent.memory.entity.AgentEventEntity;
import com.miniagent.agent.memory.repository.AgentEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class EventDrivenConsolidationWorkerTest {

    @Test
    void legacyEventsAreGroupedAndConsolidatedByTenantAndSession() {
        AgentEventRepository events = mock(AgentEventRepository.class);
        DefaultConsolidationService consolidation = mock(DefaultConsolidationService.class);
        MemoryTaskLock lock = mock(MemoryTaskLock.class);
        when(lock.tryLock(eq("consolidation-worker"), any())).thenReturn(true);
        when(events.findByProcessedFalseOrderByCreatedAtAsc()).thenReturn(List.of(
                event("tenant-a", "session-a", null),
                event("tenant-b", "session-a", null),
                event("tenant-a", "session-a", "")));

        EventDrivenConsolidationWorker worker = new EventDrivenConsolidationWorker();
        ReflectionTestUtils.setField(worker, "eventRepository", events);
        ReflectionTestUtils.setField(worker, "consolidationService", consolidation);
        ReflectionTestUtils.setField(worker, "taskLock", lock);
        ReflectionTestUtils.setField(worker, "batchSize", 50);

        worker.processPendingEvents();

        verify(consolidation).consolidate("tenant-a", "session-a");
        verify(consolidation).consolidate("tenant-b", "session-a");
        verify(consolidation, times(2)).consolidate(any(String.class), any(String.class));
        verify(lock).unlock("consolidation-worker");
    }

    private static AgentEventEntity event(String tenantId, String sessionId, String taskId) {
        AgentEventEntity event = new AgentEventEntity();
        event.setTenantId(tenantId);
        event.setSessionId(sessionId);
        event.setTaskId(taskId);
        event.setProcessed(false);
        return event;
    }
}
