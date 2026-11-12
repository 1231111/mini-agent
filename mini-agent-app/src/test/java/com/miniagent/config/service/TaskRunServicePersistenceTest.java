package com.miniagent.config.service;

import com.miniagent.config.entity.AgentTaskRun;
import com.miniagent.config.repository.AgentTaskRunRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TaskRunServicePersistenceTest {
    @Autowired private AgentTaskRunRepository repository;

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

    private AgentTaskRun saveRunning(String sessionId) {
        AgentTaskRun run = new AgentTaskRun();
        run.setUserId(7L);
        run.setSessionId(sessionId);
        run.setStatus(AgentTaskRun.Status.RUNNING);
        return repository.saveAndFlush(run);
    }
}
