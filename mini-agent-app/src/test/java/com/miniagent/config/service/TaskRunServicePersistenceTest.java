package com.miniagent.config.service;

import com.miniagent.config.entity.AgentTaskRun;
import com.miniagent.config.repository.AgentTaskRunRepository;
import com.miniagent.replica.RedisTaskConcurrency;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TaskRunServicePersistenceTest {
    @Autowired private AgentTaskRunRepository repository;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    void terminalCommitKeepsSessionAndQuotaReservedUntilRealTransactionCompletes() throws Exception {
        AgentTaskRunRepository observed = mock(AgentTaskRunRepository.class,
                org.mockito.AdditionalAnswers.delegatesTo(repository));
        TaskRunService service = transactionalService(observed);
        ReflectionTestUtils.setField((Object) org.springframework.test.util.AopTestUtils.getTargetObject(service),
                "maxPerUser", 1);
        TaskRunService.StartResult started = service.tryStart(7L, "terminal-commit-gate");
        assertTrue(started.accepted(), started.error());
        CountDownLatch committing = new CountDownLatch(1);
        CountDownLatch commitAllowed = new CountDownLatch(1);
        AtomicBoolean first = new AtomicBoolean(true);
        doAnswer(call -> {
            int updated = repository.finishIfRunning(call.getArgument(0), call.getArgument(1),
                    call.getArgument(2), call.getArgument(3), call.getArgument(4));
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            if (first.getAndSet(false)) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void beforeCommit(boolean readOnly) {
                        committing.countDown();
                        try { assertTrue(commitAllowed.await(5, TimeUnit.SECONDS)); }
                        catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(interrupted);
                        }
                    }
                });
            }
            return updated;
        }).when(observed).finishIfRunning(any(), any(), any(), any(), any());

        try (var workers = Executors.newSingleThreadExecutor()) {
            var completion = workers.submit(() -> service.markCompleted(started.handle()));
            try {
                assertTrue(committing.await(5, TimeUnit.SECONDS));
                assertTrue(service.isRunning(started.handle().sessionId()), "commit has not released ownership");
                assertEquals(started.handle().fencingToken(),
                        service.activeRunToken(started.handle().sessionId()).orElseThrow());
                assertFalse(service.tryStart(7L, started.handle().sessionId()).accepted());
                assertFalse(service.tryStart(7L, "same-user-during-terminal-commit").accepted(),
                        "the finishing run still owns its quota until commit");
                assertEquals(AgentTaskRun.Status.RUNNING,
                        repository.findById(started.handle().runId()).orElseThrow().getStatus());
            } finally {
                commitAllowed.countDown();
            }
            completion.get(5, TimeUnit.SECONDS);
        } finally {
            commitAllowed.countDown();
        }
        assertFalse(service.isRunning(started.handle().sessionId()));
        assertEquals(AgentTaskRun.Status.COMPLETED,
                repository.findById(started.handle().runId()).orElseThrow().getStatus());
        TaskRunService.StartResult next = service.tryStart(7L, started.handle().sessionId());
        assertTrue(next.accepted(), next.error());
        service.markCancelled(next.handle(), "test cleanup");
    }

    @ParameterizedTest
    @EnumSource(value = AgentTaskRun.Status.class, names = {"COMPLETED", "FAILED", "CANCELLED", "WAITING"})
    void terminalRequiresNewCommitSurvivesOuterTransactionRollback(AgentTaskRun.Status terminal) {
        TaskRunService service = transactionalService(repository);
        TaskRunService.StartResult started = service.tryStart(7L, "outer-rollback-" + terminal);
        assertTrue(started.accepted(), started.error());

        new TransactionTemplate(transactionManager).executeWithoutResult(outer -> {
            switch (terminal) {
                case COMPLETED -> service.markCompleted(started.handle());
                case FAILED -> service.markFailed(started.handle(), "test failure");
                case CANCELLED -> service.markCancelled(started.handle(), "test cancellation");
                case WAITING -> service.markWaiting(started.handle(), "test human wait");
                default -> fail("unexpected terminal status");
            }
            assertFalse(service.isRunning(started.handle().sessionId()));
            outer.setRollbackOnly();
        });

        assertEquals(terminal, repository.findById(started.handle().runId()).orElseThrow().getStatus(),
                "an unrelated outer rollback must not restore an unlocked RUNNING row");
    }

    @Test
    void terminalCommitFailureReleasesOnlyAfterRealRollbackAndAllowsRecovery() {
        AgentTaskRunRepository observed = mock(AgentTaskRunRepository.class,
                org.mockito.AdditionalAnswers.delegatesTo(repository));
        TaskRunService service = transactionalService(observed);
        TaskRunService.StartResult started = service.tryStart(7L, "terminal-commit-failure");
        assertTrue(started.accepted(), started.error());
        AtomicBoolean rolledBack = new AtomicBoolean();
        AtomicBoolean first = new AtomicBoolean(true);
        doAnswer(call -> {
            int updated = repository.finishIfRunning(call.getArgument(0), call.getArgument(1),
                    call.getArgument(2), call.getArgument(3), call.getArgument(4));
            if (first.getAndSet(false)) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void beforeCommit(boolean readOnly) {
                        assertTrue(service.isRunning(started.handle().sessionId()));
                        throw new IllegalStateException("injected commit failure");
                    }
                    @Override public void afterCompletion(int status) {
                        assertEquals(STATUS_ROLLED_BACK, status);
                        rolledBack.set(true);
                    }
                });
            }
            return updated;
        }).when(observed).finishIfRunning(any(), any(), any(), any(), any());

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> service.markCompleted(started.handle()));

        assertEquals("injected commit failure", failure.getMessage());
        assertTrue(rolledBack.get());
        assertFalse(service.isRunning(started.handle().sessionId()));
        assertEquals(AgentTaskRun.Status.RUNNING,
                repository.findById(started.handle().runId()).orElseThrow().getStatus());
        TaskRunService.StartResult replacement = service.tryStart(7L, started.handle().sessionId());
        assertTrue(replacement.accepted(), replacement.error());
        assertEquals(AgentTaskRun.Status.INTERRUPTED,
                repository.findById(started.handle().runId()).orElseThrow().getStatus());
        service.markCancelled(replacement.handle(), "test cleanup");
    }

    private TaskRunService transactionalService(AgentTaskRunRepository targetRepository) {
        TaskRunService target = new TaskRunService();
        ReflectionTestUtils.setField(target, "repository", targetRepository);
        ReflectionTestUtils.setField(target, "transactionManager", transactionManager);
        TransactionInterceptor transactions = new TransactionInterceptor();
        transactions.setTransactionManager(transactionManager);
        transactions.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(transactions);
        TaskRunService proxy = (TaskRunService) factory.getProxy();
        assertTrue(AopUtils.isAopProxy(proxy));
        return proxy;
    }

    @Test
    void terminalCompareAndSetStartsItsOwnTransactionAndRetainsCompletedStatus() {
        AgentTaskRun run = saveRunning("cas-without-outer-transaction");
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());

        assertEquals(1, repository.finishIfRunning(run.getId(), AgentTaskRun.Status.RUNNING,
                AgentTaskRun.Status.COMPLETED, LocalDateTime.now(), null));
        assertEquals(0, repository.finishIfRunning(run.getId(), AgentTaskRun.Status.RUNNING,
                AgentTaskRun.Status.INTERRUPTED, LocalDateTime.now(), "stale recovery"));

        AgentTaskRun restored = repository.findById(run.getId()).orElseThrow();
        assertEquals(AgentTaskRun.Status.COMPLETED, restored.getStatus());
        assertNotNull(restored.getFinishedAt());
        assertNull(restored.getErrorMessage());
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    }

    @Test
    void localAdmissionRecoversAnOrphanWithoutAnOuterTransaction() {
        AgentTaskRun orphan = saveRunning("local-admission-without-outer-transaction");
        TaskRunService service = new TaskRunService();
        ReflectionTestUtils.setField(service, "repository", repository);
        ReflectionTestUtils.setField(service, "transactionManager", transactionManager);
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());

        TaskRunService.StartResult started = service.tryStart(7L, orphan.getSessionId());

        assertTrue(started.accepted(), started.error());
        AgentTaskRun recovered = repository.findById(orphan.getId()).orElseThrow();
        assertEquals(AgentTaskRun.Status.INTERRUPTED, recovered.getStatus());
        assertTrue(recovered.getErrorMessage().contains("Local run ownership lost"));
        assertEquals(AgentTaskRun.Status.RUNNING,
                repository.findById(started.handle().runId()).orElseThrow().getStatus());
        service.markCompleted(started.handle());
        assertEquals(AgentTaskRun.Status.COMPLETED,
                repository.findById(started.handle().runId()).orElseThrow().getStatus());
        assertFalse(service.isRunning(orphan.getSessionId()));
    }

    @Test
    void expiredAdmissionReplyCannotInterruptItsReplacementOrReleaseItsToken() throws Exception {
        String sessionId = "late-quota-admission-reply";
        RedisTaskConcurrency concurrency = mock(RedisTaskConcurrency.class);
        TaskRunService oldReplica = new TaskRunService();
        ReflectionTestUtils.setField(oldReplica, "repository", repository);
        ReflectionTestUtils.setField(oldReplica, "transactionManager", transactionManager);
        ReflectionTestUtils.setField(oldReplica, "taskConcurrency", concurrency);
        CountDownLatch quotaEntered = new CountDownLatch(1);
        CountDownLatch quotaReply = new CountDownLatch(1);
        AtomicReference<String> currentToken = new AtomicReference<>();
        when(concurrency.tryLockSession(eq(sessionId), anyString())).thenAnswer(call -> {
            currentToken.set(call.getArgument(1));
            return true;
        });
        when(concurrency.tryOccupyUserQuota(eq(7L), eq(sessionId), anyString(), anyInt())).thenAnswer(call -> {
            quotaEntered.countDown();
            assertTrue(quotaReply.await(3, TimeUnit.SECONDS));
            return true; // successful command whose reply was delayed beyond its lease
        });
        when(concurrency.renewSessionLock(eq(sessionId), anyString())).thenAnswer(call ->
                call.getArgument(1).equals(currentToken.get()));
        doAnswer(call -> {
            currentToken.compareAndSet(call.getArgument(1), null);
            return null;
        }).when(concurrency).unlockSession(eq(sessionId), anyString());
        try (var workers = Executors.newSingleThreadExecutor()) {
            var lateAdmission = workers.submit(() -> oldReplica.tryStart(7L, sessionId));
            assertTrue(quotaEntered.await(3, TimeUnit.SECONDS));
            // A different replica acquires the expired Redis lock and commits its RUNNING row.
            currentToken.set("replacement-token");
            AgentTaskRun replacement = saveRunning(sessionId);
            quotaReply.countDown();

            TaskRunService.StartResult result = lateAdmission.get(3, TimeUnit.SECONDS);

            assertFalse(result.accepted());
            assertEquals("replacement-token", currentToken.get(), "stale cleanup must compare tokens");
            assertEquals(AgentTaskRun.Status.RUNNING,
                    repository.findById(replacement.getId()).orElseThrow().getStatus());
            assertEquals(1L, repository.findByStatus(AgentTaskRun.Status.RUNNING).stream()
                    .filter(run -> sessionId.equals(run.getSessionId())).count());
            verify(concurrency, never()).unlockSession(sessionId, "replacement-token");
            verify(concurrency, never()).releaseUserQuota(7L, sessionId, "replacement-token");
        } finally {
            quotaReply.countDown();
        }
    }

    private AgentTaskRun saveRunning(String sessionId) {
        AgentTaskRun run = new AgentTaskRun();
        run.setUserId(7L);
        run.setSessionId(sessionId);
        run.setStatus(AgentTaskRun.Status.RUNNING);
        return repository.saveAndFlush(run);
    }
}
