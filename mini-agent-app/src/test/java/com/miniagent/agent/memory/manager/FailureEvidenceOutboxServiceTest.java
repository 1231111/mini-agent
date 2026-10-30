package com.miniagent.agent.memory.manager;

import com.miniagent.agent.memory.entity.*;
import com.miniagent.agent.memory.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FailureEvidenceOutboxServiceTest {
    private AgentFailureEvidenceOutboxRepository outbox;
    private AgentProcedureApplicationRepository applications;
    private AgentProcedureRepository procedures;
    private FailureEvidenceOutboxService service;

    @SuppressWarnings("unchecked")
    @BeforeEach void setUp() {
        outbox = mock(AgentFailureEvidenceOutboxRepository.class);
        applications = mock(AgentProcedureApplicationRepository.class);
        procedures = mock(AgentProcedureRepository.class);
        ObjectProvider<RemoteProcedureSettlementPort> remote = mock(ObjectProvider.class);
        service = new FailureEvidenceOutboxService(outbox, applications, procedures,
                remote, mock(PlatformTransactionManager.class));
    }

    @Test void enqueuesOnlyUniqueFailureApplicationWithMatchingUserProcedureAndVersion() {
        AgentEpisodeEntity episode = episode();
        AgentProcedureApplicationEntity application = application();
        AgentProcedureEntity procedure = procedure();
        when(applications.findByTenantIdAndSessionIdAndTaskIdAndUserIdAndResult(
                "tenant-a", "session-a", 7L, 9L, AgentProcedureApplicationEntity.Result.FAILURE))
                .thenReturn(List.of(application));
        when(procedures.findById(22L)).thenReturn(Optional.of(procedure));
        when(outbox.findByTenantIdAndUserIdAndSessionIdAndTaskIdAndNodeIdAndProcedureIdAndProcedureVersion(
                "tenant-a", 9L, "session-a", 7L, "node-a", 22L, 3)).thenReturn(Optional.empty());
        when(outbox.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.enqueue(episode);

        var captor = org.mockito.ArgumentCaptor.forClass(AgentFailureEvidenceOutboxEntity.class);
        verify(outbox).save(captor.capture());
        assertEquals("tenant-a", captor.getValue().getTenantId());
        assertEquals(9L, captor.getValue().getUserId());
        assertEquals(22L, captor.getValue().getProcedureId());
        assertEquals("resolution", captor.getValue().getResolution());
        assertEquals(AgentFailureEvidenceOutboxEntity.Status.PENDING, captor.getValue().getStatus());
    }

    @Test void skipsAmbiguousOrCrossTenantApplicationAndDoesNotCreateOutbox() {
        AgentEpisodeEntity episode = episode();
        when(applications.findByTenantIdAndSessionIdAndTaskIdAndUserIdAndResult(anyString(), anyString(),
                anyLong(), anyLong(), any())).thenReturn(List.of(application(), application()));
        service.enqueue(episode);
        verifyNoInteractions(procedures);
        verify(outbox, never()).save(any());
    }

    @Test void repeatedEnqueueIsIdempotent() {
        AgentEpisodeEntity episode = episode();
        when(applications.findByTenantIdAndSessionIdAndTaskIdAndUserIdAndResult(anyString(), anyString(),
                anyLong(), anyLong(), any())).thenReturn(List.of(application()));
        when(procedures.findById(22L)).thenReturn(Optional.of(procedure()));
        when(outbox.findByTenantIdAndUserIdAndSessionIdAndTaskIdAndNodeIdAndProcedureIdAndProcedureVersion(
                anyString(), anyLong(), anyString(), anyLong(), anyString(), anyLong(), anyInt()))
                .thenReturn(Optional.of(new AgentFailureEvidenceOutboxEntity()));
        service.enqueue(episode);
        verify(outbox, never()).save(any());
    }

    private AgentEpisodeEntity episode() {
        AgentEpisodeEntity e = new AgentEpisodeEntity(); e.setId(5L); e.setTenantId("tenant-a");
        e.setUserId("9"); e.setSessionId("session-a"); e.setTaskId("7");
        e.setOutcome(AgentEpisodeEntity.Outcome.FAILURE); e.setFailureCode("TASK_FAILURE");
        e.setTaskSummary("summary"); e.setResolution("resolution"); return e;
    }
    private AgentProcedureApplicationEntity application() {
        AgentProcedureApplicationEntity a = new AgentProcedureApplicationEntity(); a.setTenantId("tenant-a");
        a.setUserId(9L); a.setSessionId("session-a"); a.setTaskId(7L); a.setNodeId("node-a");
        a.setProcedureId(22L); a.setProcedureVersion(3); a.setResult(AgentProcedureApplicationEntity.Result.FAILURE);
        return a;
    }
    private AgentProcedureEntity procedure() {
        AgentProcedureEntity p = new AgentProcedureEntity(); p.setTenantId("tenant-a");
        p.setScopeType(AgentMemoryEntryEntity.ScopeType.USER); p.setScopeId("9"); p.setProcedureVersion(3);
        return p;
    }
}
