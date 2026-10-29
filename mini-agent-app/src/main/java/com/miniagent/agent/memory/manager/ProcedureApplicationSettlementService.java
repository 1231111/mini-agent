package com.miniagent.agent.memory.manager;

import com.miniagent.agent.memory.entity.AgentMemoryEntryEntity;
import com.miniagent.agent.memory.entity.AgentProcedureApplicationEntity;
import com.miniagent.agent.memory.repository.AgentProcedureApplicationRepository;
import com.miniagent.agent.memory.repository.AgentProcedureRepository;
import com.miniagent.memory.port.MemoryPrincipal;
import com.miniagent.memory.port.ProcedureSettlementCommand;
import com.miniagent.memory.port.ProcedureSettlementPort;
import com.miniagent.memory.port.ProcedureSettlementResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 把 planner 的节点终态结算成 Procedure 的应用事实与效果统计。
 *
 * <p><b>事实与统计刻意分离</b>：应用记录（{@code agent_procedure_applications}）是
 * "这一版 Procedure 在这个节点上被用了一次、结果是怎样"的事实；{@code attempt_count}
 * / {@code success_count} / {@code success_rate} 是可从事实重算的派生统计。
 * 当统计更新没命中（Procedure 被删除、版本已升级、归属人变了）时，只跳过统计并记日志，
 * <b>不能让事实回滚，也不能打断任务</b> —— 否则用户删掉一条 SOP 就会让正在跑的任务失败。</p>
 *
 * <p><b>幂等怎么保证</b>：唯一键 {@code ux_apa_attempt}
 * （tenant_id, user_id, session_id, task_id, node_id, procedure_id, procedure_version）
 * 是最终裁判。写入放在 {@link TransactionDefinition#PROPAGATION_REQUIRES_NEW} 的独立事务里：
 * 先查后插省掉绝大多数重复，真正的并发窗口由唯一键兜底 —— 冲突只会回滚这一个内层事务，
 * 外层的事务不会被标记 rollback-only，统计照常进行。</p>
 *
 * <p>不用 {@code INSERT ... ON DUPLICATE KEY UPDATE}：那是 MySQL 专有语法，
 * H2（桌面档）即使开 {@code MODE=MySQL} 也会报语法错误。</p>
 */
@Service
public class ProcedureApplicationSettlementService implements ProcedureSettlementPort {

    private static final Logger log =
            LoggerFactory.getLogger(ProcedureApplicationSettlementService.class);

    private final AgentProcedureApplicationRepository applications;
    private final AgentProcedureRepository procedures;
    private final ProcedureGovernanceService governance;
    private final ProcedureGovernanceProperties governanceProperties;
    private final ProcedureRevisionService revisionService;
    private final TransactionTemplate requiresNew;

    public ProcedureApplicationSettlementService(AgentProcedureApplicationRepository applications,
                                                  AgentProcedureRepository procedures,
                                                  PlatformTransactionManager transactionManager,
                                                  ProcedureGovernanceService governance,
                                                  ProcedureGovernanceProperties governanceProperties,
                                                  ProcedureRevisionService revisionService) {
        this.applications = applications;
        this.procedures = procedures;
        this.governance = governance;
        this.governanceProperties = governanceProperties;
        this.revisionService = revisionService;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    @Transactional
    public ProcedureSettlementResult settle(ProcedureSettlementCommand command) {
        MemoryPrincipal principal = command.principal();
        if (!tryRecordApplication(principal, command)) {
            return ProcedureSettlementResult.duplicate();
        }

        procedures.findById(command.procedureId())
                .filter(p -> principal.tenantId().equals(p.getTenantId()))
                .ifPresent(p -> revisionService.capture(p, "APPLICATION",
                        principal.sessionId() + ":" + command.taskId(), principal.userId()));
        int updated = procedures.bumpSettlement(command.procedureId(), principal.tenantId(),
                AgentMemoryEntryEntity.ScopeType.USER, String.valueOf(principal.userId()),
                command.procedureVersion(), command.outcome() == ProcedureSettlementCommand.Outcome.SUCCESS ? 1 : 0);
        if (updated != 1) {
            // 应用事实已经落库并提交，这里只是统计没命中。
            // 绝不再抛异常：那会连带回滚上面的 insert，把"确实用过一次"的事实也抹掉。
            log.warn("Procedure 结算未命中，跳过统计: procedure={}, version={}, tenant={}, owner={}, node={}",
                    command.procedureId(), command.procedureVersion(), principal.tenantId(),
                    principal.userId(), command.nodeId());
            return ProcedureSettlementResult.staleProcedure();
        }
        procedures.refreshSuccessRate(command.procedureId(), principal.tenantId());
        // 第 1 步的治理动作只有「停止推荐明显失效的 SOP」，不改写内容。
        // 每个 owner 的 ACTIVE SOP 数量是有界集合；CAS 会在数据库里重验版本和判据。
        // 失败只影响治理，不回滚刚完成的节点，也不抹掉应用事实/统计。
        try {
            if (governanceProperties.isEnabled()) {
                governance.archiveUnderperforming(principal.tenantId(), principal.userId());
            }
        } catch (Exception e) {
            log.warn("Procedure 自动治理失败，不影响节点结算: procedure={}, tenant={}, owner={}",
                    command.procedureId(), principal.tenantId(), principal.userId(), e);
        }
        return ProcedureSettlementResult.recorded();
    }

    /**
     * @return true 表示本次真的新建了应用事实；false 表示这个节点在这一版 Procedure 上已结算过。
     */
    private boolean tryRecordApplication(MemoryPrincipal principal, ProcedureSettlementCommand command) {
        try {
            Boolean inserted = requiresNew.execute(status -> {
                if (applications
                        .existsByTenantIdAndUserIdAndSessionIdAndTaskIdAndNodeIdAndProcedureIdAndProcedureVersion(
                                principal.tenantId(), principal.userId(), principal.sessionId(), command.taskId(),
                                command.nodeId(), command.procedureId(), command.procedureVersion())) {
                    return false;
                }
                AgentProcedureApplicationEntity entity = new AgentProcedureApplicationEntity();
                entity.setTenantId(principal.tenantId());
                entity.setUserId(principal.userId());
                entity.setSessionId(principal.sessionId());
                entity.setTaskId(command.taskId());
                entity.setNodeId(command.nodeId());
                entity.setProcedureId(command.procedureId());
                entity.setProcedureVersion(command.procedureVersion());
                entity.setActionId(command.actionId());
                entity.setResult(command.outcome() == ProcedureSettlementCommand.Outcome.SUCCESS
                        ? AgentProcedureApplicationEntity.Result.SUCCESS
                        : AgentProcedureApplicationEntity.Result.FAILURE);
                applications.saveAndFlush(entity);
                return true;
            });
            return Boolean.TRUE.equals(inserted);
        } catch (DataIntegrityViolationException duplicate) {
            // 并发窗口里另一个执行器先写入了同一条尝试。唯一键已经拦住，视为重复。
            return false;
        }
    }
}
