package com.miniagent.agent.memory.manager;

import com.miniagent.agent.memory.entity.AgentMemoryEntryEntity;
import com.miniagent.agent.memory.repository.AgentProcedureApplicationRepository;
import com.miniagent.agent.memory.repository.AgentProcedureRepository;
import com.miniagent.agent.planner.DoneWhen;
import com.miniagent.agent.planner.NodeTerminalCommitPort;
import com.miniagent.agent.planner.TaskNode;
import com.miniagent.agent.planner.TaskNodeStatus;
import com.miniagent.common.context.OwnerContext;
import com.miniagent.memory.port.MemoryPrincipal;
import com.miniagent.memory.port.ProcedureSettlementCommand;
import com.miniagent.memory.port.ProcedureSettlementResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProcedureApplicationSettlementServiceTest {
    private final AgentProcedureApplicationRepository applications = mock(AgentProcedureApplicationRepository.class);
    private final AgentProcedureRepository procedures = mock(AgentProcedureRepository.class);
    private final PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
    private final ProcedureGovernanceService governance = mock(ProcedureGovernanceService.class);
    private final ProcedureGovernanceProperties governanceProperties = new ProcedureGovernanceProperties();
    private final ProcedureRevisionService revisions = mock(ProcedureRevisionService.class);
    private final ProcedureApplicationSettlementService service =
            new ProcedureApplicationSettlementService(applications, procedures, txManager,
                    governance, governanceProperties, revisions);
    private final ProcedureNodeTerminalCommitAdapter adapter =
            new ProcedureNodeTerminalCommitAdapter(service);

    @BeforeEach
    void allowNestedTransaction() {
        // TransactionTemplate 只用到 getTransaction；提交/回滚对 mock 是 no-op。
        when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    }

    @Test
    void duplicateTerminalSettlementUpdatesCountersOnlyOnce() {
        when(applications
                .existsByTenantIdAndUserIdAndSessionIdAndTaskIdAndNodeIdAndProcedureIdAndProcedureVersion(
                        anyString(), anyLong(), anyString(), anyLong(), anyString(), anyLong(), anyInt()))
                .thenReturn(false, true);
        when(procedures.bumpSettlement(anyLong(), anyString(), any(), anyString(), anyInt(), anyInt()))
                .thenReturn(1);
        when(procedures.refreshSuccessRate(anyLong(), anyString())).thenReturn(1);

        try (var ignored = OwnerContext.bind(new OwnerContext(9L, "tenant-a"))) {
            adapter.commit("session", 4L, "action", node(), NodeTerminalCommitPort.Result.SUCCESS);
            adapter.commit("session", 4L, "action", node(), NodeTerminalCommitPort.Result.SUCCESS);
        }

        verify(applications, times(1)).saveAndFlush(any());
        verify(procedures, times(1)).bumpSettlement(22L, "tenant-a",
                AgentMemoryEntryEntity.ScopeType.USER, "9", 2, 1);
        verify(procedures, times(1)).refreshSuccessRate(22L, "tenant-a");
        verify(governance, times(1)).archiveUnderperforming("tenant-a", 9L);
    }

    @Test
    void failureIsSettledAsFailureAndProcedureUpdateIncludesOwnerAndTenant() {
        when(applications
                .existsByTenantIdAndUserIdAndSessionIdAndTaskIdAndNodeIdAndProcedureIdAndProcedureVersion(
                        anyString(), anyLong(), anyString(), anyLong(), anyString(), anyLong(), anyInt()))
                .thenReturn(false);
        when(procedures.bumpSettlement(anyLong(), anyString(), any(), anyString(), anyInt(), anyInt()))
                .thenReturn(1);
        when(procedures.refreshSuccessRate(anyLong(), anyString())).thenReturn(1);

        try (var ignored = OwnerContext.bind(new OwnerContext(9L, "tenant-a"))) {
            adapter.commit("session", 4L, "action", node(), NodeTerminalCommitPort.Result.FAILURE);
        }

        verify(procedures).bumpSettlement(22L, "tenant-a", AgentMemoryEntryEntity.ScopeType.USER,
                "9", 2, 0);
        verify(governance, times(1)).archiveUnderperforming("tenant-a", 9L);
    }

    @Test
    void missingOwnerDoesNotWriteAnything() {
        adapter.commit("session", 4L, "action", node(), NodeTerminalCommitPort.Result.SUCCESS);
        verifyNoInteractions(applications, procedures);
    }

    /**
     * 统计没命中（Procedure 被删 / 版本已升级 / 归属变更）时不能抛：
     * 上面那条应用事实已经提交，抛异常会连带把它回滚掉，
     * 而 planner 侧的 settleNode 又会把异常当成任务失败。
     */
    @Test
    void staleProcedureSkipsCountersWithoutThrowing() {
        when(applications
                .existsByTenantIdAndUserIdAndSessionIdAndTaskIdAndNodeIdAndProcedureIdAndProcedureVersion(
                        anyString(), anyLong(), anyString(), anyLong(), anyString(), anyLong(), anyInt()))
                .thenReturn(false);
        when(procedures.bumpSettlement(anyLong(), anyString(), any(), anyString(), anyInt(), anyInt()))
                .thenReturn(0);

        try (var ignored = OwnerContext.bind(new OwnerContext(9L, "tenant-a"))) {
            assertDoesNotThrow(() -> adapter.commit("session", 4L, "action", node(),
                    NodeTerminalCommitPort.Result.SUCCESS));
        }
        verify(procedures, never()).refreshSuccessRate(anyLong(), anyString());
        verifyNoInteractions(governance);
    }

    /**
     * 并发窗口：预检没看到、插入时被唯一键拦住。此时必须视为「已结算」，
     * 而不是把 DataIntegrityViolationException 抛给 planner。
     */
    @Test
    void governanceFailureDoesNotFailTheCompletedNodeOrLoseSettlement() {
        when(applications
                .existsByTenantIdAndUserIdAndSessionIdAndTaskIdAndNodeIdAndProcedureIdAndProcedureVersion(
                        anyString(), anyLong(), anyString(), anyLong(), anyString(), anyLong(), anyInt()))
                .thenReturn(false);
        when(procedures.bumpSettlement(anyLong(), anyString(), any(), anyString(), anyInt(), anyInt()))
                .thenReturn(1);
        when(procedures.refreshSuccessRate(anyLong(), anyString())).thenReturn(1);
        doThrow(new IllegalStateException("governance unavailable"))
                .when(governance).archiveUnderperforming("tenant-a", 9L);

        try (var ignored = OwnerContext.bind(new OwnerContext(9L, "tenant-a"))) {
            assertDoesNotThrow(() -> adapter.commit("session", 4L, "action", node(),
                    NodeTerminalCommitPort.Result.SUCCESS));
        }

        verify(applications).saveAndFlush(any());
        verify(procedures).bumpSettlement(22L, "tenant-a", AgentMemoryEntryEntity.ScopeType.USER,
                "9", 2, 1);
        verify(procedures).refreshSuccessRate(22L, "tenant-a");
    }

    @Test
    void narrowPortReturnsRecordedAndDuplicateStatuses() {
        when(applications
                .existsByTenantIdAndUserIdAndSessionIdAndTaskIdAndNodeIdAndProcedureIdAndProcedureVersion(
                        anyString(), anyLong(), anyString(), anyLong(), anyString(), anyLong(), anyInt()))
                .thenReturn(false, true);
        when(procedures.bumpSettlement(anyLong(), anyString(), any(), anyString(), anyInt(), anyInt()))
                .thenReturn(1);
        when(procedures.refreshSuccessRate(anyLong(), anyString())).thenReturn(1);
        ProcedureSettlementCommand command = new ProcedureSettlementCommand(
                new MemoryPrincipal("tenant-a", 9L, "project-a", "session"),
                4L, "node", "action", 22L, 2, ProcedureSettlementCommand.Outcome.SUCCESS);

        assertEquals(ProcedureSettlementResult.Status.RECORDED, service.settle(command).status());
        assertEquals(ProcedureSettlementResult.Status.DUPLICATE, service.settle(command).status());
    }

    @Test
    void uniqueKeyRaceIsTreatedAsAlreadySettled() {
        when(applications
                .existsByTenantIdAndUserIdAndSessionIdAndTaskIdAndNodeIdAndProcedureIdAndProcedureVersion(
                        anyString(), anyLong(), anyString(), anyLong(), anyString(), anyLong(), anyInt()))
                .thenReturn(false);
        when(applications.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("ux_apa_attempt"));

        try (var ignored = OwnerContext.bind(new OwnerContext(9L, "tenant-a"))) {
            // 重复结算被吞掉：提交路径不抛，也不再加统计。
            assertDoesNotThrow(() -> adapter.commit("session", 4L, "action", node(),
                    NodeTerminalCommitPort.Result.SUCCESS));
        }
        verify(procedures, never()).bumpSettlement(anyLong(), anyString(), any(), anyString(),
                anyInt(), anyInt());
        verifyNoInteractions(governance);
    }

    private static TaskNode node() {
        return new TaskNode("node", "step", "code", List.of(), List.of(), List.of(),
                TaskNodeStatus.PENDING, 1, DoneWhen.note(), "", "", 0, "")
                .withProcedure(22L, 2);
    }
}
