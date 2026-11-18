package com.miniagent.memoryservice.evolution;

import com.miniagent.memory.contract.MemoryApi.SubjectErasureResult;
import com.miniagent.memoryservice.store.AuditStore;
import com.miniagent.memoryservice.store.ItemStore;
import com.miniagent.memoryservice.store.SubjectLifecycleStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static com.miniagent.memoryservice.store.Db.ts;

/** Atomic, idempotent erasure of all user-owned records in this service's SQL database. */
@Service
public class SubjectErasureService {
    private final SubjectLifecycleStore lifecycle;
    private final ItemStore items;
    private final AuditStore audit;
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final Clock clock;

    public SubjectErasureService(SubjectLifecycleStore lifecycle, ItemStore items,
                                 AuditStore audit, JdbcTemplate jdbc, Clock clock) {
        this.lifecycle = lifecycle;
        this.items = items;
        this.audit = audit;
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
        this.clock = clock;
    }

    /**
     * The tombstone lock, deletes, and receipt commit together. A retry after a lost response
     * returns the original completion receipt. Any SQL failure rolls back both erasure and tombstone.
     */
    @Transactional
    public SubjectErasureResult erase(String tenantId, long userId) {
        Instant now = clock.instant();
        var previous = lifecycle.lockForErasure(tenantId, userId, now);
        if (previous.isPresent()) {
            var state = previous.get();
            return new SubjectErasureResult(true, state.erasedItems(), state.erasedFacts(),
                    state.erasedAt());
        }

        List<Long> itemIds = items.userScopeIds(tenantId, userId);
        List<Long> proposalIds = selectIds("SELECT id FROM evo_proposal WHERE item_id IN (:ids)",
                itemIds);

        deleteByIds("DELETE FROM evo_job WHERE item_id IN (:ids) OR proposal_id IN (:ids)",
                itemIds);
        deleteByIds("DELETE FROM evo_review WHERE proposal_id IN (:ids)", proposalIds);
        audit.deleteRegressionForItems(itemIds);
        deleteByIds("DELETE FROM evo_proposal WHERE id IN (:ids)", proposalIds);
        jdbc.update("DELETE FROM outcome_event WHERE tenant_id = ? AND user_id = ?",
                tenantId, userId);
        items.deleteUserScope(tenantId, userId);

        // The v1 proposal/evidence tables remain in upgraded databases for migration safety.
        // Their owner columns make exact user-scoped physical deletion possible.
        List<Long> legacyEvolutionProposalIds = jdbc.queryForList(
                "SELECT proposal_id FROM memory_evolution_proposals WHERE tenant_id = ?"
                        + " AND user_id = ?", Long.class, tenantId, userId);
        deleteByIds("DELETE FROM memory_evolution_trials WHERE proposal_id IN (:ids)",
                legacyEvolutionProposalIds);
        deleteByIds("DELETE FROM memory_evolution_canary_observations WHERE proposal_id IN (:ids)",
                legacyEvolutionProposalIds);
        deleteByIds("DELETE FROM memory_evolution_validation_locks WHERE proposal_id IN (:ids)",
                legacyEvolutionProposalIds);
        jdbc.update("DELETE FROM memory_evolution_validation_runs WHERE tenant_id = ? AND user_id = ?",
                tenantId, userId);
        jdbc.update("DELETE FROM memory_evolution_canary_assignments WHERE tenant_id = ? AND user_id = ?",
                tenantId, userId);
        jdbc.update("DELETE FROM memory_evolution_evidence WHERE tenant_id = ? AND user_id = ?",
                tenantId, userId);
        deleteByIds("DELETE FROM memory_evolution_proposals WHERE proposal_id IN (:ids)",
                legacyEvolutionProposalIds);

        List<Long> legacyProcedureIds = jdbc.queryForList(
                "SELECT id FROM agent_procedures WHERE tenant_id = ? AND scope_type = 'USER'"
                        + " AND scope_id = ?", Long.class, tenantId, String.valueOf(userId));
        jdbc.update("DELETE FROM agent_procedure_applications WHERE tenant_id = ? AND user_id = ?",
                tenantId, userId);
        deleteByIds("DELETE FROM agent_procedure_applications WHERE procedure_id IN (:ids)",
                legacyProcedureIds);
        deleteByIds("DELETE FROM agent_procedures WHERE id IN (:ids)", legacyProcedureIds);
        deleteAuditTargets("PROCEDURE", legacyProcedureIds, tenantId);
        deleteAuditTargets("PROPOSAL", legacyEvolutionProposalIds, tenantId);

        int erasedFacts = jdbc.update("DELETE FROM mem_user_fact WHERE tenant_id = ? AND user_id = ?",
                tenantId, userId);

        // Remove old direct identifiers and data-linked audit entries; the new receipt below
        // keeps only a one-way subject hash and aggregate counts.
        jdbc.update("DELETE FROM audit_log WHERE tenant_id = ? AND actor = ?",
                tenantId, "user:" + userId);
        jdbc.update("DELETE FROM audit_log WHERE tenant_id = ? AND target_type = 'USER'"
                        + " AND target_id = ?", tenantId, String.valueOf(userId));
        deleteAuditTargets("ITEM", itemIds, tenantId);
        deleteAuditTargets("PROPOSAL", proposalIds, tenantId);

        // Remove this user's project membership and revoke projects they owned. Project-scoped
        // content is shared and remains intact; revocation prevents stale grants from surviving.
        jdbc.update("DELETE FROM memory_project_members WHERE tenant_id = ? AND user_id = ?",
                tenantId, userId);
        jdbc.update("UPDATE memory_project_authorizations SET status = 'REVOKED',"
                        + " authorization_version = authorization_version + 1, updated_at = ?"
                        + " WHERE tenant_id = ? AND owner_user_id = ? AND status = 'ACTIVE'",
                ts(now), tenantId, userId);

        int erasedItems = itemIds.size();
        String subjectHash = lifecycle.subjectHash(tenantId, userId);
        audit.audit(tenantId, "subject-erasure", "SUBJECT_ERASED", "SUBJECT_HASH",
                subjectHash, Map.of("items", erasedItems, "facts", erasedFacts), now);
        lifecycle.markErased(tenantId, userId, erasedItems, erasedFacts, now);
        return new SubjectErasureResult(true, erasedItems, erasedFacts, now);
    }

    private List<Long> selectIds(String sql, List<Long> ids) {
        if (ids.isEmpty()) return List.of();
        return named.queryForList(sql, new MapSqlParameterSource("ids", ids), Long.class);
    }

    private void deleteByIds(String sql, List<Long> ids) {
        if (!ids.isEmpty()) {
            named.update(sql, new MapSqlParameterSource("ids", ids));
        }
    }

    private void deleteAuditTargets(String type, List<Long> ids, String tenantId) {
        if (!ids.isEmpty()) {
            List<String> targetIds = ids.stream().map(String::valueOf).toList();
            named.update("DELETE FROM audit_log WHERE tenant_id = :tenant"
                            + " AND target_type = :type AND target_id IN (:ids)",
                    new MapSqlParameterSource("tenant", tenantId).addValue("type", type)
                            .addValue("ids", targetIds));
        }
    }
}
