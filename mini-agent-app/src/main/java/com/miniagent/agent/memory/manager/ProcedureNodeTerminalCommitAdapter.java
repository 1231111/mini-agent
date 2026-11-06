package com.miniagent.agent.memory.manager;

import com.miniagent.agent.memory.entity.AgentProcedureEntity;
import com.miniagent.agent.memory.repository.AgentProcedureRepository;
import com.miniagent.agent.planner.NodeTerminalCommitPort;
import com.miniagent.agent.planner.TaskNode;
import com.miniagent.common.context.OwnerContext;
import com.miniagent.memory.contract.MemoryApi.OutcomeEvent;
import com.miniagent.memory.contract.MemoryApi.OutcomeResult;
import com.miniagent.memory.port.MemoryPrincipal;
import com.miniagent.memory.port.ProcedureSettlementCommand;
import com.miniagent.memory.port.ProcedureSettlementPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * 节点终态先写入已有的本地记忆。配了云端时，同一结果再送给云端，供它保存和升级。
 * 降级快照（snapshot 带 fallback=true）不送云端。
 */
@Component
public class ProcedureNodeTerminalCommitAdapter implements NodeTerminalCommitPort {
    private static final Logger log = LoggerFactory.getLogger(ProcedureNodeTerminalCommitAdapter.class);
    private static final String FALLBACK_MARK = "\"fallback\":true";
    private static final String TASK_SEPARATOR = ":";

    private final ProcedureSettlementPort settlementPort;
    private final ObjectProvider<OutcomeOutboxService> outbox;
    private final ObjectProvider<CloudMemoryClient> cloud;
    private final ObjectProvider<AgentProcedureRepository> procedures;

    public ProcedureNodeTerminalCommitAdapter(ProcedureSettlementPort settlementPort,
                                              ObjectProvider<OutcomeOutboxService> outbox,
                                              ObjectProvider<CloudMemoryClient> cloud,
                                              ObjectProvider<AgentProcedureRepository> procedures) {
        this.settlementPort = settlementPort;
        this.outbox = outbox;
        this.cloud = cloud;
        this.procedures = procedures;
    }

    @Override
    public void commit(String sessionId, long taskId, String actionId, TaskNode node, Result result) {
        commit(sessionId, taskId, actionId, node, result, null);
    }

    @Override
    public void commit(String sessionId, long taskId, String actionId, TaskNode node,
                       Result result, Detail detail) {
        OwnerContext owner = OwnerContext.capture();
        if (owner.userId() == null || owner.tenantId() == null || owner.tenantId().isBlank()
                || sessionId == null || node == null || node.procedureId() == null
                || node.procedureVersion() == null) {
            return;
        }
        try {
            settlementPort.settle(localCommand(owner, sessionId, taskId, actionId, node, result));
        } catch (RuntimeException e) {
            log.warn("Procedure 结算服务不可用，跳过本次结算: session={}, task={}, node={}, reason={}",
                    sessionId, taskId, node.id(), e.getClass().getSimpleName());
        }
        try {
            enqueueCloud(owner, sessionId, taskId, node, result, detail);
        } catch (RuntimeException e) {
            log.warn("结果未送到云端: session={}, task={}, node={}, reason={}",
                    sessionId, taskId, node.id(), e.getClass().getSimpleName());
        }
    }

    private void enqueueCloud(OwnerContext owner, String sessionId, long taskId, TaskNode node,
                              Result result, Detail detail) {
        OutcomeOutboxService queue = outbox.getIfAvailable();
        if (queue == null) {
            return;
        }
        String snapshot = node.procedureSnapshotJson();
        if (snapshot != null && snapshot.contains(FALLBACK_MARK)) {
            return;
        }
        AgentProcedureRepository repository = procedures.getIfAvailable();
        if (repository == null) {
            return;
        }
        AgentProcedureEntity row = repository.findById(node.procedureId()).orElse(null);
        if (row == null || row.getCloudItemId() == null || row.getCloudRevisionNo() == null) {
            return;
        }
        String errorCode = detail == null ? null : detail.errorCode();
        String reason = detail == null ? null : detail.failureReason();
        String message = detail == null ? null : detail.userMessage();
        OutcomeResult outcome = result == Result.SUCCESS
                ? OutcomeResult.SUCCESS : OutcomeResult.FAILURE;
        CloudMemoryClient client = cloud.getIfAvailable();
        String env = client == null ? null : client.envVersion();
        queue.enqueue(new OutcomeEvent(
                UUID.randomUUID().toString(), owner.tenantId(), owner.userId(),
                OwnerContext.currentProjectId(), row.getCloudItemId(), row.getCloudRevisionNo(),
                null, null, 0, sessionId + TASK_SEPARATOR + taskId, node.id(),
                outcome, errorCode, reason, message, node.name(), env, Instant.now()));
    }

    private static ProcedureSettlementCommand localCommand(OwnerContext owner, String sessionId,
                                                           long taskId, String actionId,
                                                           TaskNode node, Result result) {
        return new ProcedureSettlementCommand(
                new MemoryPrincipal(owner.tenantId(), owner.userId(),
                        OwnerContext.currentProjectId(), sessionId),
                taskId, node.id(), actionId, node.procedureId(), node.procedureVersion(),
                result == Result.SUCCESS
                        ? ProcedureSettlementCommand.Outcome.SUCCESS
                        : ProcedureSettlementCommand.Outcome.FAILURE,
                node.evolutionProposalId(),
                node.evolutionCandidateHash().isBlank() ? null : node.evolutionCandidateHash(),
                node.procedureVariant());
    }
}
