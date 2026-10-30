package com.miniagent.memory.service.persistence;

import com.miniagent.memory.port.MemoryPrincipal;
import com.miniagent.memory.port.ProcedureSettlementCommand;
import com.miniagent.memory.port.ProcedureSettlementPort;
import com.miniagent.memory.port.ProcedureSettlementResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 记忆服务侧的 Procedure 结算实现。
 *
 * <p><b>归属从投影读，不从请求读</b>：请求只带 {@code procedureId} + {@code procedureVersion}。
 * 这一行属于 {@code USER} 还是 {@code PROJECT}、{@code scope_id} 是谁，都以服务端已收敛的投影为准。
 * 如果信任请求里的 scope，任何持有内部密钥的调用方都能把结算记到别人的 SOP 上。</p>
 *
 * <p><b>为什么先校验归属再写应用事实</b>：越权请求不该在 {@code agent_procedure_applications}
 * 里留下一条“这次真的用了”的证据。先失败，再落任何数据。</p>
 *
 * <p>应用事实和版本统计在同一事务内提交，候选变体只保留独立事实，不污染基线 Procedure 统计。
 * 结算按 procedure_version 做 CAS 条件，迟到结算不会把旧版本的成绩写到新版本。</p>
 */
@Service
@Profile("memory-service")
public class DatabaseProcedureSettlementPort implements ProcedureSettlementPort {
    private static final Logger log = LoggerFactory.getLogger(DatabaseProcedureSettlementPort.class);

    private final JdbcTemplate jdbc;

    public DatabaseProcedureSettlementPort(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public ProcedureSettlementResult settle(ProcedureSettlementCommand command) {
        validateEvolutionIdentity(command);
        if (command.evolutionProposalId() != null && !isActiveCandidate(command)) {
            return ProcedureSettlementResult.staleProcedure();
        }
        List<ProcedureScope> scopes = jdbc.query(
                "SELECT scope_type, scope_id FROM agent_procedures "
                        + "WHERE id = ? AND tenant_id = ? AND procedure_version = ?",
                (rs, rowNum) -> new ProcedureScope(rs.getString("scope_type"), rs.getString("scope_id")),
                command.procedureId(), command.principal().tenantId(), command.procedureVersion());
        if (scopes.isEmpty()) {
            // 投影还没到，或这一版已被更新版本取代。两种情况都不能改计数。
            return ProcedureSettlementResult.staleProcedure();
        }
        ProcedureScope scope = scopes.getFirst();
        if (!isAuthorized(scope, command.principal())) {
            log.warn("Procedure 结算跳过：调用用户不在服务端授权快照中。procedure={} version={} scope={}/{} userId={}",
                    command.procedureId(), command.procedureVersion(), scope.type(), scope.id(),
                    command.principal().userId());
            return ProcedureSettlementResult.skipped();
        }

        try {
            int inserted = jdbc.update("INSERT INTO agent_procedure_applications "
                                + "(tenant_id,user_id,session_id,task_id,node_id,procedure_id,procedure_version,"
                                + "action_id,result,evolution_proposal_id,evolution_candidate_hash,procedure_variant,created_at) "
                                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",
                        command.principal().tenantId(), command.principal().userId(),
                        command.principal().sessionId(), command.taskId(), command.nodeId(),
                        command.procedureId(), command.procedureVersion(), command.actionId(),
                        command.outcome().name(), command.evolutionProposalId(), command.evolutionCandidateHash(),
                        command.procedureVariant());
            if (inserted != 1) {
                return ProcedureSettlementResult.duplicate();
            }
        } catch (DuplicateKeyException duplicate) {
            return ProcedureSettlementResult.duplicate();
        }

        if ("CANDIDATE".equals(command.procedureVariant())) {
            return ProcedureSettlementResult.recorded();
        }

        int updated = jdbc.update("UPDATE agent_procedures SET "
                        + "success_count = success_count + ?, attempt_count = attempt_count + 1, "
                        + "usage_count = usage_count + 1 "
                        + "WHERE id = ? AND tenant_id = ? AND scope_type = ? AND scope_id = ? "
                        + "AND procedure_version = ?",
                command.outcome() == ProcedureSettlementCommand.Outcome.SUCCESS ? 1 : 0,
                command.procedureId(), command.principal().tenantId(),
                scope.type(), scope.id(), command.procedureVersion());
        if (updated != 1) {
            // 读到这里之间被升版并发改掉了版本号：应用事实已落库，但统计不再属于这一版。
            return ProcedureSettlementResult.staleProcedure();
        }
        // 比率必须由下一条语句按已提交计数重算，不能和上面的累加写在同一条 UPDATE 里
        // （MySQL 按赋值列表从左到右求值，会读到已加过的值）。
        jdbc.update("UPDATE agent_procedures SET success_rate = "
                        + "CASE WHEN attempt_count > 0 THEN success_count * 1.0 / attempt_count ELSE 0.0 END "
                        + "WHERE id = ? AND tenant_id = ?",
                command.procedureId(), command.principal().tenantId());
        return ProcedureSettlementResult.recorded();
    }

    private boolean isActiveCandidate(ProcedureSettlementCommand command) {
        Integer matches = jdbc.queryForObject("""
                SELECT COUNT(*) FROM memory_evolution_proposals
                 WHERE tenant_id=? AND user_id=? AND procedure_id=? AND proposal_id=?
                   AND candidate_hash=? AND status='CANARY'
                """, Integer.class, command.principal().tenantId(), command.principal().userId(),
                command.procedureId(), command.evolutionProposalId(), command.evolutionCandidateHash());
        return matches != null && matches == 1;
    }

    private static void validateEvolutionIdentity(ProcedureSettlementCommand command) {
        if (command.evolutionProposalId() == null) {
            if (command.evolutionCandidateHash() != null || !"BASELINE".equals(command.procedureVariant())) {
                throw new IllegalArgumentException("baseline settlement cannot carry candidate identity");
            }
            return;
        }
        if (command.evolutionProposalId() <= 0 || command.evolutionCandidateHash() == null
                || !command.evolutionCandidateHash().matches("[0-9a-f]{64}")
                || !"CANDIDATE".equals(command.procedureVariant())) {
            throw new IllegalArgumentException("candidate settlement identity is invalid");
        }
    }

    private boolean isAuthorized(ProcedureScope scope, MemoryPrincipal principal) {
        if (ProcedureScopeRules.USER.equalsIgnoreCase(scope.type())) {
            return scope.id() != null && scope.id().equals(String.valueOf(principal.userId()));
        }
        if (!ProcedureScopeRules.PROJECT.equalsIgnoreCase(scope.type())
                || scope.id() == null || principal.projectId() == null
                || !scope.id().equals(principal.projectId())) {
            return false;
        }
        List<Long> ownerIds = jdbc.query(
                "SELECT owner_user_id FROM memory_project_authorizations "
                        + "WHERE tenant_id=? AND project_id=? AND status='ACTIVE' FOR UPDATE",
                (rs, rowNum) -> rs.getLong("owner_user_id"), principal.tenantId(), scope.id());
        if (ownerIds.isEmpty()) {
            return false;
        }
        if (ownerIds.getFirst() == principal.userId()) {
            return true;
        }
        return !jdbc.query(
                "SELECT 1 FROM memory_project_members "
                        + "WHERE tenant_id=? AND project_id=? AND user_id=?",
                (rs, rowNum) -> 1, principal.tenantId(), scope.id(), principal.userId()).isEmpty();
    }

    private record ProcedureScope(String type, String id) {
    }
}
