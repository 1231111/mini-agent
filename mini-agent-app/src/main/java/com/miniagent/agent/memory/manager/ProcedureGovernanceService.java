package com.miniagent.agent.memory.manager;

import com.miniagent.agent.memory.entity.AgentMemoryEntryEntity;
import com.miniagent.agent.memory.entity.AgentProcedureEntity;
import com.miniagent.agent.memory.repository.AgentProcedureRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 基于已结算节点的真实表现，自动停止向规划器推荐明显失效的 SOP。
 *
 * <p><b>第 1 步只做归档，不改写 SOP 内容</b>：样本不足或结果尚可时不动；达到配置的最小结算数、
 * 且当前版本的 successRate 不高于阈值时，将 ACTIVE 改为 ARCHIVED。归档是可逆的，
 * {@code ProcedureUpsertService} 收到同名新内容时会复用原行并升版复活；这里不会删除 SOP、
 * 不覆盖原始步骤、不自动生成未经验证的新步骤。</p>
 *
 * <p>触发点是一个新的、幂等通过的节点结算之后。重复结算不增加样本，故不会重复触发；
 * 判据与归档在同一事务内，最终由 repository 的版本 + 状态 + 样本 + 比率条件 CAS 保护。</p>
 */
@Service
public class ProcedureGovernanceService {

    private static final Logger log = LoggerFactory.getLogger(ProcedureGovernanceService.class);

    private final AgentProcedureRepository procedures;
    private final ProcedureGovernanceProperties properties;

    public ProcedureGovernanceService(AgentProcedureRepository procedures,
                                      ProcedureGovernanceProperties properties) {
        this.procedures = procedures;
        this.properties = properties;
    }

    /** 结算后检查该 owner 的 ACTIVE SOP；失败隔离由调用者保证。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int archiveUnderperforming(String tenantId, Long userId) {
        if (tenantId == null || tenantId.isBlank() || userId == null) {
            return 0;
        }
        String owner = String.valueOf(userId);
        List<AgentProcedureEntity> active = procedures.findByTenantIdAndScopeTypeAndScopeIdAndStatus(
                tenantId, AgentMemoryEntryEntity.ScopeType.USER, owner,
                AgentMemoryEntryEntity.Status.ACTIVE);
        int archived = 0;
        for (AgentProcedureEntity procedure : active) {
            long attempts = procedure.getAttemptCount() == null ? 0 : procedure.getAttemptCount();
            double successRate = procedure.getSuccessRate() == null ? 0.0 : procedure.getSuccessRate();
            if (attempts < properties.getMinAttempts()
                    || successRate > properties.getArchiveMaxSuccessRate()) {
                continue;
            }
            int updated = procedures.archiveIfStillUnderperforming(procedure.getId(), tenantId,
                    AgentMemoryEntryEntity.ScopeType.USER, owner, procedure.getProcedureVersion(),
                    properties.getMinAttempts(), properties.getArchiveMaxSuccessRate(),
                    AgentMemoryEntryEntity.Status.ACTIVE, AgentMemoryEntryEntity.Status.ARCHIVED);
            if (updated == 1) {
                archived++;
                log.warn("自动归档低成功率 Procedure: id={}, version={}, tenant={}, owner={}, attempts={}, successRate={}",
                        procedure.getId(), procedure.getProcedureVersion(), tenantId, userId,
                        attempts, successRate);
            }
        }
        return archived;
    }
}
