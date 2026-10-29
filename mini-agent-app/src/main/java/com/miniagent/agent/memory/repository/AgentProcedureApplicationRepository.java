package com.miniagent.agent.memory.repository;

import com.miniagent.agent.memory.entity.AgentProcedureApplicationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 应用尝试表。
 *
 * <p><b>为什么没有 {@code INSERT ... ON DUPLICATE KEY UPDATE}</b>：那是 MySQL 专有语法，
 * H2 即使开 {@code MODE=MySQL} 也不认（实测 {@code Syntax error ... [*]on duplicate key update}），
 * 而桌面档跑的就是 H2 —— 一旦用原生 upsert，桌面端的每一次节点结算都会抛 SQL 错误。
 * 幂等因此交给「唯一键 + 独立事务试探插入」，由 Service 层实现，两个库行为一致。</p>
 */
public interface AgentProcedureApplicationRepository extends JpaRepository<AgentProcedureApplicationEntity, Long> {

    boolean existsByTenantIdAndUserIdAndSessionIdAndTaskIdAndNodeIdAndProcedureIdAndProcedureVersion(
            String tenantId, Long userId, String sessionId, Long taskId, String nodeId,
            Long procedureId, Integer procedureVersion);

    List<AgentProcedureApplicationEntity> findByTenantIdAndSessionIdAndTaskIdAndUserIdAndResult(
            String tenantId, String sessionId, Long taskId, Long userId,
            AgentProcedureApplicationEntity.Result result);
}
