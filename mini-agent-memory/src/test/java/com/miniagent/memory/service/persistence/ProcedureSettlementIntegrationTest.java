package com.miniagent.memory.service.persistence;

import com.miniagent.memory.port.MemoryPrincipal;
import com.miniagent.memory.port.ProcedureSettlementCommand;
import com.miniagent.memory.port.ProcedureSettlementPort;
import com.miniagent.memory.port.ProcedureSettlementResult;
import com.miniagent.memory.service.web.ProcedureDefinitionRequest;
import com.miniagent.memory.service.web.ProcedureDefinitionResponse;
import com.miniagent.memory.service.persistence.ProjectAuthorizationSynchronizer;
import com.miniagent.memory.service.web.ProjectAuthorizationRequest;
import com.miniagent.memory.service.web.ProjectAuthorizationResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 用真实 H2 + Flyway 验证 Procedure 纵向切片。
 *
 * <p>不 mock {@code JdbcTemplate}：这条链路的价值全在 SQL 语义上——版本条件、计数累加、
 * 唯一键幂等、赋值顺序。mock 掉之后测的是自己写的 when/then，不是数据库实际行为。</p>
 */
@SpringBootTest(classes = com.miniagent.memory.service.MemoryServiceApplication.class)
@ActiveProfiles("memory-service")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:memorysvc;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "memory.service.security.internal-key=test-key"
})
class ProcedureSettlementIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ProcedureDefinitionSynchronizer synchronizer;
    @Autowired
    private ProcedureSettlementPort settlementPort;
    @Autowired
    private ProjectAuthorizationSynchronizer authorizationSynchronizer;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM agent_procedure_applications");
        jdbc.update("DELETE FROM memory_project_members");
        jdbc.update("DELETE FROM memory_project_authorizations");
        jdbc.update("DELETE FROM agent_procedures");
    }

    @Test
    void projectionCreatesRowWithZeroCounters() {
        ProcedureDefinitionResponse response = synchronizer.synchronize(definition(1L, 1, "USER", "7", "steps-v1"));

        assertEquals(ProcedureDefinitionResponse.Status.CREATED, response.status());
        Map<String, Object> row = row(1L);
        assertEquals(0L, ((Number) row.get("success_count")).longValue());
        assertEquals(0L, ((Number) row.get("attempt_count")).longValue());
        assertEquals(1, ((Number) row.get("procedure_version")).intValue());
    }

    @Test
    void repeatedProjectionOfSameVersionIsIdempotent() {
        synchronizer.synchronize(definition(1L, 1, "USER", "7", "steps-v1"));
        ProcedureDefinitionResponse second = synchronizer.synchronize(definition(1L, 1, "USER", "7", "steps-v1"));

        assertEquals(ProcedureDefinitionResponse.Status.UNCHANGED, second.status());
    }

    @Test
    void sameVersionWithDifferentContentIsAConflict() {
        synchronizer.synchronize(definition(1L, 1, "USER", "7", "steps-v1"));
        ProcedureDefinitionResponse conflict = synchronizer.synchronize(definition(1L, 1, "USER", "7", "other-steps"));

        assertEquals(ProcedureDefinitionResponse.Status.CONFLICT, conflict.status());
        assertEquals("[\"steps-v1\"]", jdbc.queryForObject(
                "SELECT steps_json FROM agent_procedures WHERE id = 1", String.class));
    }

    @Test
    void newVersionResetsCountersThatBelongedToTheOldContent() {
        synchronizer.synchronize(definition(1L, 1, "USER", "7", "steps-v1"));
        settlementPort.settle(settlement(1L, 1, 7L, null, "n0", ProcedureSettlementCommand.Outcome.SUCCESS));
        assertEquals(1L, ((Number) row(1L).get("success_count")).longValue());

        ProcedureDefinitionResponse upgrade = synchronizer.synchronize(
                definition(1L, 2, "USER", "7", "steps-v2"));

        assertEquals(ProcedureDefinitionResponse.Status.UPDATED, upgrade.status());
        Map<String, Object> row = row(1L);
        assertEquals(2, ((Number) row.get("procedure_version")).intValue());
        assertEquals(0L, ((Number) row.get("success_count")).longValue());
        assertEquals(0L, ((Number) row.get("attempt_count")).longValue());
        assertEquals(0.0, ((Number) row.get("success_rate")).doubleValue(), 1e-9);
    }

    @Test
    void olderProjectionIsReportedStaleAndDoesNotRollBackContent() {
        synchronizer.synchronize(definition(1L, 2, "USER", "7", "steps-v2"));

        ProcedureDefinitionResponse stale = synchronizer.synchronize(definition(1L, 1, "USER", "7", "steps-v1"));

        assertEquals(ProcedureDefinitionResponse.Status.STALE, stale.status());
        assertEquals("[\"steps-v2\"]", jdbc.queryForObject(
                "SELECT steps_json FROM agent_procedures WHERE id = 1", String.class));
    }

    @Test
    void settlementAccumulatesCountersAndRecomputesRate() {
        synchronizer.synchronize(definition(1L, 1, "USER", "7", "steps-v1"));

        assertEquals(ProcedureSettlementResult.Status.RECORDED,
                settlementPort.settle(settlement(1L, 1, 7L, null, "n1", ProcedureSettlementCommand.Outcome.SUCCESS)).status());
        assertEquals(ProcedureSettlementResult.Status.RECORDED,
                settlementPort.settle(settlement(1L, 1, 7L, null, "n2", ProcedureSettlementCommand.Outcome.FAILURE)).status());

        Map<String, Object> row = row(1L);
        assertEquals(1L, ((Number) row.get("success_count")).longValue());
        assertEquals(2L, ((Number) row.get("attempt_count")).longValue());
        assertEquals(0.5, ((Number) row.get("success_rate")).doubleValue(), 1e-9);
    }

    @Test
    void repeatedSettlementOfSameNodeIsDuplicateAndDoesNotDoubleCount() {
        synchronizer.synchronize(definition(1L, 1, "USER", "7", "steps-v1"));

        settlementPort.settle(settlement(1L, 1, 7L, null, "same-node", ProcedureSettlementCommand.Outcome.SUCCESS));
        ProcedureSettlementResult second = settlementPort.settle(
                settlement(1L, 1, 7L, null, "same-node", ProcedureSettlementCommand.Outcome.SUCCESS));

        assertEquals(ProcedureSettlementResult.Status.DUPLICATE, second.status());
        assertEquals(1L, ((Number) row(1L).get("attempt_count")).longValue());
    }

    @Test
    void settlementOfOutdatedVersionDoesNotTouchCurrentCounters() {
        synchronizer.synchronize(definition(1L, 1, "USER", "7", "steps-v1"));
        synchronizer.synchronize(definition(1L, 2, "USER", "7", "steps-v2"));

        ProcedureSettlementResult result = settlementPort.settle(
                settlement(1L, 1, 7L, null, "n-old", ProcedureSettlementCommand.Outcome.SUCCESS));

        assertEquals(ProcedureSettlementResult.Status.STALE_PROCEDURE, result.status());
        assertEquals(0L, ((Number) row(1L).get("attempt_count")).longValue());
    }

    @Test
    void settlementForAnotherUserIsSkippedAndLeavesNoApplicationRow() {
        synchronizer.synchronize(definition(1L, 1, "USER", "7", "steps-v1"));

        ProcedureSettlementResult result = settlementPort.settle(
                settlement(1L, 1, 8L, null, "n-other", ProcedureSettlementCommand.Outcome.SUCCESS));

        assertEquals(ProcedureSettlementResult.Status.SKIPPED, result.status());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_procedure_applications", Integer.class));
        assertEquals(0L, ((Number) row(1L).get("attempt_count")).longValue());
    }

    /**
     * PROJECT 结算必须依据记忆服务已提交的授权快照；未授权和已撤权请求都不能落应用事实。
     */
    @Test
    void projectScopedSettlementRequiresCurrentServerAuthorizationSnapshot() {
        synchronizer.synchronize(definition(2L, 1, "PROJECT", "proj-a", "steps-p1"));

        ProcedureSettlementResult unauthorized = settlementPort.settle(
                settlement(2L, 1, 7L, "proj-a", "n-denied", ProcedureSettlementCommand.Outcome.SUCCESS));
        assertEquals(ProcedureSettlementResult.Status.SKIPPED, unauthorized.status());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM agent_procedure_applications", Integer.class));

        jdbc.update("INSERT INTO memory_project_authorizations "
                        + "(tenant_id,project_id,owner_user_id,authorization_version,status) VALUES (?,?,?,?, 'ACTIVE')",
                "tenant", "proj-a", 9L, 1L);
        jdbc.update("INSERT INTO memory_project_members "
                        + "(tenant_id,project_id,user_id,authorization_version) VALUES (?,?,?,?)",
                "tenant", "proj-a", 7L, 1L);
        ProcedureSettlementResult member = settlementPort.settle(
                settlement(2L, 1, 7L, "proj-a", "n-member", ProcedureSettlementCommand.Outcome.SUCCESS));
        assertEquals(ProcedureSettlementResult.Status.RECORDED, member.status());

        jdbc.update("DELETE FROM memory_project_members WHERE tenant_id=? AND project_id=? AND user_id=?",
                "tenant", "proj-a", 7L);
        jdbc.update("UPDATE memory_project_authorizations SET authorization_version=2 WHERE tenant_id=? AND project_id=?",
                "tenant", "proj-a");
        ProcedureSettlementResult revoked = settlementPort.settle(
                settlement(2L, 1, 7L, "proj-a", "n-revoked", ProcedureSettlementCommand.Outcome.SUCCESS));
        assertEquals(ProcedureSettlementResult.Status.SKIPPED, revoked.status());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM agent_procedure_applications", Integer.class));
        assertEquals(1L, ((Number) row(2L).get("attempt_count")).longValue());
    }

    @Test
    void settlementAndRevocationSerializeOnTheAuthorizationRow() {
        synchronizer.synchronize(definition(3L, 1, "PROJECT", "proj-lock", "steps-p1"));
        authorizationSynchronizer.synchronize(new ProjectAuthorizationRequest(
                "tenant", "proj-lock", 9L, java.util.List.of(7L), 1L));

        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        ProcedureSettlementResult result = transaction.execute(status -> {
            ProcedureSettlementResult settled = settlementPort.settle(settlement(
                    3L, 1, 7L, "proj-lock", "n-before-revoke", ProcedureSettlementCommand.Outcome.SUCCESS));
            authorizationSynchronizer.synchronize(new ProjectAuthorizationRequest(
                    "tenant", "proj-lock", 9L, java.util.List.of(), 2L));
            return settled;
        });

        assertEquals(ProcedureSettlementResult.Status.RECORDED, result.status());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM agent_procedure_applications", Integer.class));
        assertEquals(1L, ((Number) row(3L).get("attempt_count")).longValue());

        ProcedureSettlementResult afterRevoke = settlementPort.settle(settlement(
                3L, 1, 7L, "proj-lock", "n-after-revoke", ProcedureSettlementCommand.Outcome.SUCCESS));
        assertEquals(ProcedureSettlementResult.Status.SKIPPED, afterRevoke.status());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM agent_procedure_applications", Integer.class));
    }

    @Test
    void authorizationSnapshotsRejectStaleUpdatesAndSameVersionContentConflicts() {
        ProjectAuthorizationRequest first = new ProjectAuthorizationRequest("tenant", "proj-a", 9L,
                java.util.List.of(7L), 1L);
        assertEquals(ProjectAuthorizationResponse.Status.CREATED,
                authorizationSynchronizer.synchronize(first).status());
        assertEquals(ProjectAuthorizationResponse.Status.UNCHANGED,
                authorizationSynchronizer.synchronize(first).status());
        assertEquals(ProjectAuthorizationResponse.Status.CONFLICT,
                authorizationSynchronizer.synchronize(new ProjectAuthorizationRequest(
                        "tenant", "proj-a", 9L, java.util.List.of(8L), 1L)).status());
        assertEquals(ProjectAuthorizationResponse.Status.UPDATED,
                authorizationSynchronizer.synchronize(new ProjectAuthorizationRequest(
                        "tenant", "proj-a", 9L, java.util.List.of(8L), 2L)).status());
        assertEquals(ProjectAuthorizationResponse.Status.STALE,
                authorizationSynchronizer.synchronize(first).status());
        assertEquals(8L, jdbc.queryForObject(
                "SELECT user_id FROM memory_project_members WHERE tenant_id=? AND project_id=?",
                Long.class, "tenant", "proj-a"));
    }

    @Test
    void userScopeMustMatchTheDeclaredUser() {
        ProcedureDefinitionResponse response = synchronizer.synchronize(
                definition(1L, 1, "USER", "8", "steps-v1"));

        assertEquals(ProcedureDefinitionResponse.Status.CONFLICT, response.status());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM agent_procedures", Integer.class));
    }

    @Test
    void candidateSettlementIsPersistedSeparatelyFromBaselineAndIdempotentPerVariant() {
        synchronizer.synchronize(definition(1L, 1, "USER", "7", "steps-v1"));
        jdbc.update("INSERT INTO memory_evolution_proposals "
                        + "(tenant_id,user_id,procedure_id,base_version,candidate_id,base_content_json,candidate_content_json,"
                        + "candidate_hash,generator_version,evidence_ids_json,status,created_at,updated_at) "
                        + "VALUES ('tenant',7,1,1,'candidate-91','{}','{}',?,'test','[]','CANARY',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                "b".repeat(64));
        Long proposalId = jdbc.queryForObject("SELECT proposal_id FROM memory_evolution_proposals "
                + "WHERE tenant_id='tenant' AND candidate_id='candidate-91'", Long.class);
        assertEquals(1L, proposalId);
        ProcedureSettlementCommand baseline = settlement(1L, 1, 7L, null, "same-node",
                ProcedureSettlementCommand.Outcome.SUCCESS);
        ProcedureSettlementCommand candidate = new ProcedureSettlementCommand(
                baseline.principal(), baseline.taskId(), baseline.nodeId(), baseline.actionId(),
                baseline.procedureId(), baseline.procedureVersion(), baseline.outcome(),
                proposalId, "b".repeat(64), "CANDIDATE");

        assertEquals(ProcedureSettlementResult.Status.RECORDED, settlementPort.settle(candidate).status());
        assertEquals(ProcedureSettlementResult.Status.RECORDED, settlementPort.settle(baseline).status());
        assertEquals(ProcedureSettlementResult.Status.DUPLICATE, settlementPort.settle(candidate).status());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM agent_procedure_applications", Integer.class));
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM agent_procedure_applications "
                + "WHERE procedure_variant='CANDIDATE' AND evolution_proposal_id=? AND evolution_candidate_hash=?",
                Long.class, proposalId, "b".repeat(64)));
        assertEquals(1L, ((Number) row(1L).get("attempt_count")).longValue());
    }

    private Map<String, Object> row(long procedureId) {
        return jdbc.queryForMap("SELECT * FROM agent_procedures WHERE id = ?", procedureId);
    }

    private static ProcedureDefinitionRequest definition(long id, int version, String scopeType,
                                                         String scopeId, String step) {
        return new ProcedureDefinitionRequest(
                "tenant", 7L, id, version, scopeType, scopeId, "Deploy checklist",
                "desc", "[\"pre-1\"]", "[\"" + step + "\"]", "[\"ok\"]", 0.6, "ACTIVE");
    }

    private static ProcedureSettlementCommand settlement(long procedureId, int version, long userId,
                                                         String projectId, String nodeId,
                                                         ProcedureSettlementCommand.Outcome outcome) {
        return new ProcedureSettlementCommand(
                new MemoryPrincipal("tenant", userId, projectId, "session-" + userId + "-" + procedureId),
                100L,
                nodeId,
                "action",
                procedureId,
                version,
                outcome);
    }
}
