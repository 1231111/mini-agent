package com.miniagent.agent.memory.manager;

import com.miniagent.agent.planner.NodeTerminalCommitPort;
import com.miniagent.agent.planner.TaskNode;
import com.miniagent.common.context.OwnerContext;
import com.miniagent.memory.port.MemoryPrincipal;
import com.miniagent.memory.port.ProcedureSettlementCommand;
import com.miniagent.memory.port.ProcedureSettlementPort;
import org.springframework.stereotype.Component;

/**
 * Planner 到记忆服务端口的进程内适配器。
 *
 * <p>Planner 只知道自己的 {@link NodeTerminalCommitPort}；记忆实现只知道
 * {@link ProcedureSettlementPort}。未来把记忆实现移到云端时，只需替换后一个端口的实现，
 * 不需要让云端服务依赖 Planner 的 TaskNode。</p>
 */
@Component
public class ProcedureNodeTerminalCommitAdapter implements NodeTerminalCommitPort {
    private final ProcedureSettlementPort settlementPort;

    public ProcedureNodeTerminalCommitAdapter(ProcedureSettlementPort settlementPort) {
        this.settlementPort = settlementPort;
    }

    @Override
    public void commit(String sessionId, long taskId, String actionId, TaskNode node, Result result) {
        OwnerContext owner = OwnerContext.capture();
        if (owner.userId() == null || owner.tenantId() == null || owner.tenantId().isBlank()
                || sessionId == null || node == null || node.procedureId() == null
                || node.procedureVersion() == null) {
            return;
        }
        settlementPort.settle(new ProcedureSettlementCommand(
                new MemoryPrincipal(owner.tenantId(), owner.userId(), OwnerContext.currentProjectId(), sessionId),
                taskId,
                node.id(),
                actionId,
                node.procedureId(),
                node.procedureVersion(),
                result == Result.SUCCESS
                        ? ProcedureSettlementCommand.Outcome.SUCCESS
                        : ProcedureSettlementCommand.Outcome.FAILURE));
    }
}
