package com.miniagent.agent.memory;

import com.miniagent.agent.memory.entity.AgentMemoryIndexOutboxEntity;
import com.miniagent.agent.memory.repository.AgentMemoryIndexOutboxRepository;
import com.miniagent.agent.memory.retriever.MilvusHybridSearchEngine;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MemoryIndexOutboxServiceTest {

    @Test
    void drainReclaimsOnlyExpiredProcessingLeasesBeforeReadingPendingRows() {
        var repository = mock(AgentMemoryIndexOutboxRepository.class);
        var service = new MemoryIndexOutboxService();
        var lock = mock(MemoryTaskLock.class);
        var milvus = mock(MilvusHybridSearchEngine.class);
        when(lock.tryLock(eq("memory-index-outbox"), any())).thenReturn(true);
        when(repository.findTop50ByStatusAndNextAttemptAtLessThanEqualOrderByIdAsc(
                eq(AgentMemoryIndexOutboxEntity.Status.PENDING), any(LocalDateTime.class)))
                .thenReturn(List.of());
        ReflectionTestUtils.setField(service, "outboxRepository", repository);
        ReflectionTestUtils.setField(service, "taskLock", lock);
        ReflectionTestUtils.setField(service, "milvus", milvus);

        service.drain();

        verify(repository).requeueStaleProcessing(
                eq(AgentMemoryIndexOutboxEntity.Status.PROCESSING),
                eq(AgentMemoryIndexOutboxEntity.Status.PENDING),
                any(LocalDateTime.class), any(LocalDateTime.class));
        verify(repository).findTop50ByStatusAndNextAttemptAtLessThanEqualOrderByIdAsc(
                eq(AgentMemoryIndexOutboxEntity.Status.PENDING), any(LocalDateTime.class));
        verify(repository).findTop500ByStatusAndUpdatedAtBeforeOrderByIdAsc(
                eq(AgentMemoryIndexOutboxEntity.Status.DONE), any(LocalDateTime.class));
        verify(lock).unlock("memory-index-outbox");
    }

    @Test
    void expiredProcessingCutoffIsTenMinutesOlderThanReclaimTime() {
        var repository = mock(AgentMemoryIndexOutboxRepository.class);
        var service = new MemoryIndexOutboxService();
        var lock = mock(MemoryTaskLock.class);
        when(lock.tryLock(eq("memory-index-outbox"), any())).thenReturn(true);
        when(repository.findTop50ByStatusAndNextAttemptAtLessThanEqualOrderByIdAsc(
                any(), any(LocalDateTime.class))).thenReturn(List.of());
        ReflectionTestUtils.setField(service, "outboxRepository", repository);
        ReflectionTestUtils.setField(service, "taskLock", lock);
        ReflectionTestUtils.setField(service, "milvus", mock(MilvusHybridSearchEngine.class));

        service.drain();

        var staleBefore = org.mockito.ArgumentCaptor.forClass(LocalDateTime.class);
        var now = org.mockito.ArgumentCaptor.forClass(LocalDateTime.class);
        verify(repository).requeueStaleProcessing(any(), any(), staleBefore.capture(), now.capture());
        assertThat(java.time.Duration.between(staleBefore.getValue(), now.getValue()).toMinutes())
                .isEqualTo(10);
    }

    @Test
    void completedOutboxRowsArePurgedOnlyAfterFourteenDays() {
        var repository = mock(AgentMemoryIndexOutboxRepository.class);
        var service = new MemoryIndexOutboxService();
        var lock = mock(MemoryTaskLock.class);
        when(lock.tryLock(eq("memory-index-outbox"), any())).thenReturn(true);
        when(repository.findTop50ByStatusAndNextAttemptAtLessThanEqualOrderByIdAsc(
                any(), any(LocalDateTime.class))).thenReturn(List.of());
        ReflectionTestUtils.setField(service, "outboxRepository", repository);
        ReflectionTestUtils.setField(service, "taskLock", lock);
        ReflectionTestUtils.setField(service, "milvus", mock(MilvusHybridSearchEngine.class));

        service.drain();

        var cutoff = org.mockito.ArgumentCaptor.forClass(LocalDateTime.class);
        verify(repository).findTop500ByStatusAndUpdatedAtBeforeOrderByIdAsc(
                eq(AgentMemoryIndexOutboxEntity.Status.DONE), cutoff.capture());
        assertThat(java.time.Duration.between(cutoff.getValue(), LocalDateTime.now()).toDays())
                .isBetween(13L, 14L);
        verify(repository, never()).deleteByIds(anyList());
    }
}
