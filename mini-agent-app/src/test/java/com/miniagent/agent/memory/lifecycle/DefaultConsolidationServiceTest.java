package com.miniagent.agent.memory.lifecycle;

import com.miniagent.agent.memory.entity.AgentEpisodeEntity;
import com.miniagent.agent.memory.entity.AgentEventEntity;
import com.miniagent.agent.memory.repository.AgentEpisodeRepository;
import com.miniagent.agent.memory.repository.AgentEventRepository;
import com.miniagent.common.context.OwnerContext;
import com.miniagent.config.OpenAiModelProperties;
import com.miniagent.memory.model.WorkingMemory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class DefaultConsolidationServiceTest {

    private DefaultConsolidationService service;
    private AgentEventRepository eventRepository;
    private AgentEpisodeRepository episodeRepository;
    private AgentChatTaskLock taskLock;
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        OwnerContext.clearCurrentTenant();
        OwnerContext.clearCurrentUser();
        service = new DefaultConsolidationService();
        eventRepository = mock(AgentEventRepository.class);
        episodeRepository = mock(AgentEpisodeRepository.class);
        taskLock = mock(AgentChatTaskLock.class);
        transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(mock(TransactionStatus.class));
        ReflectionTestUtils.setField(service, "eventRepository", eventRepository);
        ReflectionTestUtils.setField(service, "episodeRepository", episodeRepository);
        ReflectionTestUtils.setField(service, "episodeTaskLock", taskLock);
        ReflectionTestUtils.setField(service, "transactionManager", transactionManager);
        ReflectionTestUtils.setField(service, "objectMapper", new com.fasterxml.jackson.databind.ObjectMapper());
    }

    @Test
    void splitFactsIgnoresEmptyPartsAndNone() {
        assertEquals(List.of(), DefaultConsolidationService.splitFacts(null));
        assertEquals(List.of(), DefaultConsolidationService.splitFacts("  "));
        assertEquals(List.of(), DefaultConsolidationService.splitFacts("无"));
        assertEquals(List.of("用 Java 21", "输出 Markdown"),
                DefaultConsolidationService.splitFacts("用 Java 21; 输出 Markdown ;无"));
    }

    @Test
    void consolidatesOnlyRequestedTenantSessionAndTask() {
        when(taskLock.tryLock("tenant-a", "session-a", "7")).thenReturn(true);
        when(eventRepository.findByTenantIdAndSessionIdAndTaskIdOrderByCreatedAtAsc(
                "tenant-a", "session-a", "7")).thenReturn(List.of(event(1L, "tenant-a", "session-a", "7")));
        when(episodeRepository.findFirstByTenantIdAndSessionIdAndTaskIdOrderByCreatedAtAsc(
                "tenant-a", "session-a", "7")).thenReturn(Optional.empty());
        when(episodeRepository.save(any(AgentEpisodeEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.consolidateTask("tenant-a", "session-a", "7");

        var saved = org.mockito.ArgumentCaptor.forClass(AgentEpisodeEntity.class);
        verify(episodeRepository).save(saved.capture());
        assertEquals("tenant-a", saved.getValue().getTenantId());
        assertEquals("session-a", saved.getValue().getSessionId());
        assertEquals("7", saved.getValue().getTaskId());
        verify(eventRepository).markProcessed(List.of(1L));
        verify(transactionManager).commit(any(TransactionStatus.class));
        verify(taskLock).unlock("tenant-a", "session-a", "7");
        verify(eventRepository, never()).findByTenantIdAndSessionIdOrderByCreatedAtAsc("tenant-b", "session-a");
    }

    @Test
    void legacyConsolidationReadsAndChecksOnlyRequestedTenantSession() {
        when(eventRepository.findByTenantIdAndSessionIdOrderByCreatedAtAsc("tenant-a", "session-a"))
                .thenReturn(List.of());
        WorkingMemory workingMemory = new WorkingMemory();
        workingMemory.setTenantId("tenant-a");
        workingMemory.setSessionId("session-a");
        workingMemory.setGoal("complete task");
        WorkingMemoryManager workingMemoryManager = mock(WorkingMemoryManager.class);
        when(workingMemoryManager.get("session-a")).thenReturn(workingMemory);
        ReflectionTestUtils.setField(service, "workingMemoryManager", workingMemoryManager);
        when(episodeRepository.existsByTenantIdAndSessionId("tenant-a", "session-a")).thenReturn(false);
        when(episodeRepository.save(any(AgentEpisodeEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.consolidate("tenant-a", "session-a");

        var saved = org.mockito.ArgumentCaptor.forClass(AgentEpisodeEntity.class);
        verify(episodeRepository).save(saved.capture());
        assertEquals("tenant-a", saved.getValue().getTenantId());
        assertEquals("session-a", saved.getValue().getSessionId());
        verify(eventRepository, times(2))
                .findByTenantIdAndSessionIdOrderByCreatedAtAsc("tenant-a", "session-a");
        verify(episodeRepository).existsByTenantIdAndSessionId("tenant-a", "session-a");
        verify(episodeRepository, never()).findBySessionId(anyString());
    }

    @Test
    void legacyConsolidationSkipsWorkingMemoryOwnedByAnotherTenant() {
        when(eventRepository.findByTenantIdAndSessionIdOrderByCreatedAtAsc("tenant-a", "session-a"))
                .thenReturn(List.of());
        WorkingMemory workingMemory = new WorkingMemory();
        workingMemory.setTenantId("tenant-b");
        workingMemory.setSessionId("session-a");
        workingMemory.setGoal("foreign task");
        WorkingMemoryManager workingMemoryManager = mock(WorkingMemoryManager.class);
        when(workingMemoryManager.get("session-a")).thenReturn(workingMemory);
        ReflectionTestUtils.setField(service, "workingMemoryManager", workingMemoryManager);

        service.consolidate("tenant-a", "session-a");

        verify(episodeRepository, never()).save(any(AgentEpisodeEntity.class));
        verify(episodeRepository, never()).existsByTenantIdAndSessionId(anyString(), anyString());
        verify(eventRepository, never()).markProcessed(anyList());
    }

    @Test
    void legacyConsolidationWithoutBoundTenantIsRejected() {
        service.consolidate("session-a");

        verifyNoInteractions(eventRepository, episodeRepository, transactionManager);
    }

    @Test
    void repeatedConsolidationUpdatesExistingEpisodeRatherThanInsertingAnother() {
        AgentEpisodeEntity existing = new AgentEpisodeEntity();
        existing.setId(99L);
        existing.setAccessCount(4);
        var accessed = java.time.LocalDateTime.of(2026, 1, 1, 0, 0);
        existing.setLastAccessedAt(accessed);
        when(taskLock.tryLock("tenant-a", "session-a", "7")).thenReturn(true);
        when(eventRepository.findByTenantIdAndSessionIdAndTaskIdOrderByCreatedAtAsc(
                "tenant-a", "session-a", "7")).thenReturn(List.of(event(2L, "tenant-a", "session-a", "7")));
        when(episodeRepository.findFirstByTenantIdAndSessionIdAndTaskIdOrderByCreatedAtAsc(
                "tenant-a", "session-a", "7")).thenReturn(Optional.of(existing));
        when(episodeRepository.save(any(AgentEpisodeEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.consolidateTask("tenant-a", "session-a", "7");

        var saved = org.mockito.ArgumentCaptor.forClass(AgentEpisodeEntity.class);
        verify(episodeRepository).save(saved.capture());
        assertEquals(99L, saved.getValue().getId());
        assertEquals(4, saved.getValue().getAccessCount());
        assertEquals(accessed, saved.getValue().getLastAccessedAt());
        verify(episodeRepository, never()).saveAll(anyList());
    }

    @Test
    void lockContentionDoesNotReadOrWriteEvents() {
        when(taskLock.tryLock("tenant-a", "session-a", "7")).thenReturn(false);

        service.consolidateTask("tenant-a", "session-a", "7");

        verifyNoInteractions(eventRepository, episodeRepository, transactionManager);
        verify(taskLock, never()).unlock(anyString(), anyString(), anyString());
    }

    @Test
    void transactionFailureDoesNotCommitAndAlwaysReleasesTaskLock() {
        when(taskLock.tryLock("tenant-a", "session-a", "7")).thenReturn(true);
        when(eventRepository.findByTenantIdAndSessionIdAndTaskIdOrderByCreatedAtAsc(
                "tenant-a", "session-a", "7")).thenReturn(List.of(event(3L, "tenant-a", "session-a", "7")));
        when(episodeRepository.findFirstByTenantIdAndSessionIdAndTaskIdOrderByCreatedAtAsc(
                "tenant-a", "session-a", "7")).thenReturn(Optional.empty());
        when(episodeRepository.save(any(AgentEpisodeEntity.class))).thenReturn(null);
        doThrow(new IllegalStateException("mark failed")).when(eventRepository).markProcessed(List.of(3L));

        assertThrows(IllegalStateException.class, () -> service.consolidateTask("tenant-a", "session-a", "7"));

        verify(transactionManager, never()).commit(any(TransactionStatus.class));
        verify(transactionManager).rollback(any(TransactionStatus.class));
        verify(taskLock).unlock("tenant-a", "session-a", "7");
    }

    @Test
    void uniqueTaskIdReturnsOnlySingleNonBlankTask() {
        AgentEventEntity first = event(1L, "t", "s", "12");
        AgentEventEntity same = event(2L, "t", "s", "12");
        AgentEventEntity legacy = event(3L, "t", "s", null);
        AgentEventEntity other = event(4L, "t", "s", "13");

        assertEquals("12", DefaultConsolidationService.uniqueTaskId(List.of(first, same, legacy)));
        assertNull(DefaultConsolidationService.uniqueTaskId(List.of(first, other)));
        assertNull(DefaultConsolidationService.uniqueTaskId(List.of(legacy)));
        assertNull(DefaultConsolidationService.uniqueTaskId(List.of()));
    }

    @Test
    void keyHintMasksApiKey() {
        assertEquals("missing", OpenAiModelProperties.keyHint(null));
        assertEquals("not-configured", OpenAiModelProperties.keyHint("not-configured"));
        assertEquals("sk-1...wxyz", OpenAiModelProperties.keyHint("sk-1234567890wxyz"));
    }

    private AgentEventEntity event(Long id, String tenant, String session, String task) {
        AgentEventEntity event = new AgentEventEntity();
        event.setId(id);
        event.setTenantId(tenant);
        event.setSessionId(session);
        event.setTaskId(task);
        event.setEventType(AgentEventEntity.EventType.TOOL_EXECUTION);
        event.setActor("executor");
        event.setPayloadJson("{\"tool\":\"read_file\"}");
        event.setProcessed(false);
        return event;
    }
}
