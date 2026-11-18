package com.miniagent.application;

import com.miniagent.agent.core.ExecutionControl;
import com.miniagent.agent.memory.ChatMemoryConfig;
import com.miniagent.agent.memory.manager.CloudMemoryClient;
import com.miniagent.common.context.OwnerContext;
import com.miniagent.config.repository.UserRepository;
import com.miniagent.config.service.FileStorageService;
import com.miniagent.memory.MemoryStore;
import com.miniagent.memory.contract.MemoryApi.SubjectErasureResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SubjectErasureServiceTest {
    private JdbcTemplate jdbc;
    private SubjectErasureService service;
    private CloudMemoryClient cloud;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        var dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:subject-gate-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        dataSource.setDriverClassName("org.h2.Driver");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE memory_subject_erasure ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY,tenant_id VARCHAR(64) NOT NULL,user_id BIGINT NOT NULL,"
                + "subject_hash VARCHAR(64) NOT NULL,status VARCHAR(24) NOT NULL,"
                + "local_completed_at TIMESTAMP NULL,remote_completed_at TIMESTAMP NULL,last_error VARCHAR(1000),"
                + "updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,attempts INT NOT NULL DEFAULT 0,"
                + "next_attempt_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,lease_until TIMESTAMP NULL,"
                + "claim_token VARCHAR(36),UNIQUE(tenant_id,user_id))");

        cloud = mock(CloudMemoryClient.class);
        ObjectProvider<CloudMemoryClient> cloudProvider = mock(ObjectProvider.class);
        when(cloudProvider.getIfAvailable()).thenReturn(cloud);
        ObjectProvider<ExecutionControl> executionProvider = mock(ObjectProvider.class);
        var manager = new DataSourceTransactionManager(dataSource);
        service = new SubjectErasureService(jdbc, mock(MemoryStore.class),
                mock(FileStorageService.class), mock(UserRepository.class), cloudProvider,
                mock(ChatMemoryConfig.class), manager, executionProvider);
    }

    @AfterEach
    void clearOwnerContext() {
        OwnerContext.clearCurrentUser();
        OwnerContext.clearCurrentTenant();
    }

    @Test
    void writeGateCreatesActiveOwnerRowAndRejectsEveryErasureState() {
        service.assertWritable("tenant-a", 12L);
        assertEquals("ACTIVE", status("tenant-a", 12));

        for (String state : new String[]{"PENDING_LOCAL", "PENDING_REMOTE", "COMPLETE"}) {
            jdbc.update("UPDATE memory_subject_erasure SET status=? WHERE tenant_id=? AND user_id=?",
                    state, "tenant-a", 12L);
            assertThrows(IllegalStateException.class, () -> service.assertWritable("tenant-a", 12L), state);
        }
    }

    @Test
    void scheduledWorkerCompletesRemoteErasureAndReleasesLease() {
        insertPending("tenant-a", 12L);
        when(cloud.eraseSubject()).thenAnswer(ignored -> {
            assertEquals(12L, OwnerContext.currentUserId());
            assertEquals("tenant-a", OwnerContext.effectiveTenantId());
            return new SubjectErasureResult(true, 3, 2, Instant.now());
        });

        service.drainRemoteErasures();

        assertEquals("COMPLETE", status("tenant-a", 12));
        assertNull(jdbc.queryForObject("SELECT claim_token FROM memory_subject_erasure"
                + " WHERE tenant_id='tenant-a' AND user_id=12", String.class));
        verify(cloud).eraseSubject();
    }

    @Test
    void scheduledWorkerPersistsBackoffAfterRemoteFailure() {
        insertPending("tenant-a", 12L);
        when(cloud.eraseSubject()).thenThrow(new IllegalStateException("remote unavailable"));

        service.drainRemoteErasures();

        assertEquals("PENDING_REMOTE", status("tenant-a", 12));
        assertEquals(1, jdbc.queryForObject("SELECT attempts FROM memory_subject_erasure"
                + " WHERE tenant_id='tenant-a' AND user_id=12", Integer.class));
        assertTrue(jdbc.queryForObject("SELECT next_attempt_at>CURRENT_TIMESTAMP"
                + " FROM memory_subject_erasure WHERE tenant_id='tenant-a' AND user_id=12", Boolean.class));
        assertNull(jdbc.queryForObject("SELECT claim_token FROM memory_subject_erasure"
                + " WHERE tenant_id='tenant-a' AND user_id=12", String.class));
    }

    private void insertPending(String tenantId, long userId) {
        jdbc.update("INSERT INTO memory_subject_erasure(tenant_id,user_id,subject_hash,status,"
                        + "next_attempt_at,attempts,updated_at) VALUES(?,?,?,'PENDING_REMOTE',"
                        + "DATEADD('SECOND',-1,CURRENT_TIMESTAMP),0,CURRENT_TIMESTAMP)",
                tenantId, userId, "hash-" + userId);
    }

    private String status(String tenantId, long userId) {
        return jdbc.queryForObject("SELECT status FROM memory_subject_erasure WHERE tenant_id=? AND user_id=?",
                String.class, tenantId, userId);
    }
}
