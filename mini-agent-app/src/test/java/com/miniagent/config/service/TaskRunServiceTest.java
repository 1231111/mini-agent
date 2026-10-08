package com.miniagent.config.service;

import com.miniagent.config.entity.AgentTaskRun;
import com.miniagent.config.repository.AgentTaskRunRepository;
import com.miniagent.replica.RedisTaskConcurrency;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskRunServiceTest {

    @Test
    void terminalTransitionUsesRunIdAndFencingToken() {
        AgentTaskRunRepository repository = mock(AgentTaskRunRepository.class);
        RedisTaskConcurrency concurrency = mock(RedisTaskConcurrency.class);
        TaskRunService service = service(repository, concurrency);
        TaskRunService.RunHandle run =
                new TaskRunService.RunHandle(11L, 7L, "s1", "token-11");

        service.markCompleted(run);

        verify(repository).finishIfRunning(
                eq(11L),
                eq(AgentTaskRun.Status.RUNNING),
                eq(AgentTaskRun.Status.COMPLETED),
                any(),
                isNull());
        verify(concurrency).unlockSession("s1", "token-11");
        verify(concurrency).releaseUserQuota(7L, "s1", "token-11");
    }

    @Test
    void staleRunCannotRenewOrReleaseCurrentLocalLease() {
        AgentTaskRunRepository repository = mock(AgentTaskRunRepository.class);
        TaskRunService service = service(repository, null);
        TaskRunService.RunHandle stale =
                new TaskRunService.RunHandle(11L, 7L, "s1", "stale-token");
        @SuppressWarnings("unchecked")
        ConcurrentHashMap<String, String> locks =
                (ConcurrentHashMap<String, String>) ReflectionTestUtils.getField(
                        service, "localSessionLocks");
        @SuppressWarnings("unchecked")
        ConcurrentHashMap<Long, AtomicInteger> counts =
                (ConcurrentHashMap<Long, AtomicInteger>) ReflectionTestUtils.getField(
                        service, "localRunningCount");
        locks.put("s1", "current-token");
        counts.put(7L, new AtomicInteger(1));

        service.markCompleted(stale);

        assertFalse(service.renewSessionLock("s1", "stale-token"));
        assertTrue(service.renewSessionLock("s1", "current-token"));
        assertEquals("current-token", locks.get("s1"));
        assertEquals(1, counts.get(7L).get());
    }

    @Test
    void startupKeepsLiveReplicaRunAndRecoversOnlyExpiredLease() {
        AgentTaskRunRepository repository = mock(AgentTaskRunRepository.class);
        RedisTaskConcurrency concurrency = mock(RedisTaskConcurrency.class);
        TaskRunService service = service(repository, concurrency);
        AgentTaskRun live = run("live");
        AgentTaskRun orphan = run("orphan");
        when(repository.findByStatus(AgentTaskRun.Status.RUNNING))
                .thenReturn(List.of(live, orphan));
        when(concurrency.isSessionLocked("live")).thenReturn(true);
        when(concurrency.isSessionLocked("orphan")).thenReturn(false);

        service.recoverOrphanedRuns();

        assertEquals(AgentTaskRun.Status.RUNNING, live.getStatus());
        assertEquals(AgentTaskRun.Status.INTERRUPTED, orphan.getStatus());
        verify(repository, never()).save(live);
        verify(repository).save(orphan);
    }

    @Test
    void redisLeaseIsTheRunningSourceOfTruth() {
        AgentTaskRunRepository repository = mock(AgentTaskRunRepository.class);
        RedisTaskConcurrency concurrency = mock(RedisTaskConcurrency.class);
        TaskRunService service = service(repository, concurrency);
        when(concurrency.isSessionLocked("stale")).thenReturn(false);

        assertFalse(service.isRunning("stale"));
        verify(repository, never())
                .existsBySessionIdAndStatus("stale", AgentTaskRun.Status.RUNNING);
    }

    private static AgentTaskRun run(String sessionId) {
        AgentTaskRun run = new AgentTaskRun();
        run.setSessionId(sessionId);
        run.setStatus(AgentTaskRun.Status.RUNNING);
        return run;
    }

    private static TaskRunService service(
            AgentTaskRunRepository repository,
            RedisTaskConcurrency concurrency) {
        TaskRunService service = new TaskRunService();
        ReflectionTestUtils.setField(service, "repository", repository);
        ReflectionTestUtils.setField(service, "taskConcurrency", concurrency);
        return service;
    }
}
