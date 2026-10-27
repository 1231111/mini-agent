package com.miniagent.agent.memory.lifecycle;

import com.miniagent.agent.memory.MemoryTaskLock;
import com.miniagent.agent.memory.repository.AgentMemoryEntryRepository;
import com.miniagent.memory.MemoryManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MemoryForgettingWorkerTest {
    @Mock
    private AgentMemoryEntryRepository entryRepository;
    @Mock
    private MemoryManager memoryManager;
    @Mock
    private MemoryTaskLock taskLock;

    private MemoryForgettingWorker worker;

    @BeforeEach
    void setUp() {
        worker = new MemoryForgettingWorker();
        ReflectionTestUtils.setField(worker, "entryRepository", entryRepository);
        ReflectionTestUtils.setField(worker, "memoryManager", memoryManager);
        ReflectionTestUtils.setField(worker, "taskLock", taskLock);
    }

    @Test
    void lockUnavailableSkipsTenantScan() {
        when(taskLock.tryLock(eq("memory-forgetting-worker"), any())).thenReturn(false);

        worker.forget();

        verifyNoInteractions(entryRepository, memoryManager);
        verify(taskLock, never()).unlock(anyString());
    }

    @Test
    void forgetsEachNonblankActiveTenantAndContinuesAfterFailure() {
        when(taskLock.tryLock(eq("memory-forgetting-worker"), any())).thenReturn(true);
        when(entryRepository.findDistinctActiveTenantIds())
                .thenReturn(List.of("tenant-a", " ", "tenant-b", "tenant-c"));
        doAnswer(invocation -> {
            if ("tenant-b".equals(invocation.getArgument(0))) {
                throw new IllegalStateException("tenant failure");
            }
            return null;
        }).when(memoryManager).forget(anyString());

        worker.forget();

        verify(memoryManager).forget("tenant-a");
        verify(memoryManager).forget("tenant-b");
        verify(memoryManager).forget("tenant-c");
        verify(taskLock).unlock("memory-forgetting-worker");
    }

    @Test
    void alwaysUnlocksWhenTenantLookupFails() {
        when(taskLock.tryLock(eq("memory-forgetting-worker"), any())).thenReturn(true);
        when(entryRepository.findDistinctActiveTenantIds())
                .thenThrow(new IllegalStateException("database failure"));

        worker.forget();

        verifyNoInteractions(memoryManager);
        verify(taskLock).unlock("memory-forgetting-worker");
    }
}
