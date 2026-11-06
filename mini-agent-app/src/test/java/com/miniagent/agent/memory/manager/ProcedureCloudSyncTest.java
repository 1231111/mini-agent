package com.miniagent.agent.memory.manager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.agent.memory.entity.AgentMemoryEntryEntity;
import com.miniagent.agent.memory.entity.AgentProcedureEntity;
import com.miniagent.agent.memory.repository.AgentProcedureRepository;
import com.miniagent.agent.memory.repository.AgentProjectRepository;
import com.miniagent.common.context.OwnerContext;
import com.miniagent.memory.contract.MemoryApi.ItemPage;
import com.miniagent.memory.contract.MemoryApi.ItemView;
import com.miniagent.memory.contract.MemoryApi.ScopeType;
import com.miniagent.memory.contract.ProcedureContent;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProcedureCloudSyncTest {
    private final CloudMemoryClient cloud = mock(CloudMemoryClient.class);
    private final AgentProcedureRepository procedures = mock(AgentProcedureRepository.class);
    private final ProcedureRevisionService revisions = mock(ProcedureRevisionService.class);
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void pullWritesCloudRevisionIntoLocalStore() {
        when(procedures.findByTenantIdAndCloudItemId("t1", 7L)).thenReturn(Optional.empty());
        when(procedures.findByTenantIdAndScopeTypeAndScopeId("t1",
                AgentMemoryEntryEntity.ScopeType.USER, "3")).thenReturn(List.of());
        when(procedures.save(any())).thenAnswer(invocation -> {
            AgentProcedureEntity row = invocation.getArgument(0);
            if (row.getId() == null) {
                row.setId(11L);
            }
            return row;
        });
        when(cloud.list(null, null, 100)).thenReturn(
                new ItemPage(List.of(item(4, "导出 PDF")), null));

        try (OwnerContext.Scope ignored = OwnerContext.bind(new OwnerContext(3L, "t1"))) {
            sync().pull();
        }

        verify(procedures).save(org.mockito.ArgumentMatchers.argThat(row ->
                row.getCloudItemId() == 7L && row.getCloudRevisionNo() == 4
                        && row.getStepsJson().contains("导出 PDF")));
    }

    @Test
    void pullReplacesStoredContentWhenCloudRevisionAdvances() throws Exception {
        AgentProcedureEntity local = new AgentProcedureEntity();
        local.setId(11L);
        local.setTenantId("t1");
        local.setScopeType(AgentMemoryEntryEntity.ScopeType.USER);
        local.setScopeId("3");
        local.setName("周报");
        local.setDescription("旧");
        local.setPreconditionsJson("[]");
        local.setStepsJson(json.writeValueAsString(List.of("旧步骤")));
        local.setSuccessConditionsJson("[]");
        local.setStatus(AgentMemoryEntryEntity.Status.ACTIVE);
        local.setCloudItemId(7L);
        local.setCloudRevisionNo(2);
        local.setProcedureVersion(2);
        local.setUsageCount(3);
        when(procedures.findByTenantIdAndCloudItemId("t1", 7L)).thenReturn(Optional.of(local));
        when(procedures.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(cloud.list(null, null, 100)).thenReturn(new ItemPage(List.of(item(5, "新步骤")), null));

        try (OwnerContext.Scope ignored = OwnerContext.bind(new OwnerContext(3L, "t1"))) {
            sync().pull();
        }

        assertEquals(5, local.getCloudRevisionNo());
        assertEquals(0, local.getUsageCount());
        assertTrue(local.getStepsJson().contains("新步骤"));
        verify(revisions, atLeastOnce()).capture(local, "CLOUD_SYNC", "7", 3L);
    }

    @Test
    void pullSkipsWhenRevisionAlreadyStored() {
        AgentProcedureEntity local = new AgentProcedureEntity();
        local.setId(11L);
        local.setTenantId("t1");
        local.setScopeType(AgentMemoryEntryEntity.ScopeType.USER);
        local.setScopeId("3");
        local.setName("周报");
        local.setDescription("描述");
        local.setPreconditionsJson("[\"前提\"]");
        local.setStepsJson("[\"导出 PDF\"]");
        local.setSuccessConditionsJson("[\"完成\"]");
        local.setStatus(AgentMemoryEntryEntity.Status.ACTIVE);
        local.setCloudItemId(7L);
        local.setCloudRevisionNo(4);
        when(procedures.findByTenantIdAndCloudItemId("t1", 7L)).thenReturn(Optional.of(local));
        when(cloud.list(null, null, 100)).thenReturn(
                new ItemPage(List.of(item(4, "导出 PDF")), null));

        try (OwnerContext.Scope ignored = OwnerContext.bind(new OwnerContext(3L, "t1"))) {
            sync().pull();
        }

        verify(procedures, never()).save(any());
    }

    private ProcedureCloudSync sync() {
        return new ProcedureCloudSync(cloud, procedures, mock(AgentProjectRepository.class),
                revisions, json, noopTransactions(), 15_000L);
    }

    private static ItemView item(int revision, String step) {
        return new ItemView(7L, ScopeType.USER, "3", "周报", "ACTIVE", revision,
                new ProcedureContent("描述", List.of("前提"), List.of(step), List.of("完成")),
                revision, false, "UNKNOWN", Instant.EPOCH);
    }

    private static PlatformTransactionManager noopTransactions() {
        return new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {
            }

            @Override
            public void rollback(TransactionStatus status) {
            }
        };
    }
}
