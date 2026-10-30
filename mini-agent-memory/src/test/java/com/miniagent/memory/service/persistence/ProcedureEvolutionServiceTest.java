package com.miniagent.memory.service.persistence;

import com.miniagent.memory.service.evolution.CandidateProcedure;
import com.miniagent.memory.service.web.CandidateValidationRequest;
import com.miniagent.memory.service.web.EvolutionDecisionRequest;
import com.miniagent.memory.service.web.EvolutionResponse;
import com.miniagent.memory.service.web.FailureEvidenceRequest;
import com.miniagent.memory.service.web.FailureEvidenceResponse;
import com.miniagent.memory.service.web.ProcedureEvolutionRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = com.miniagent.memory.service.MemoryServiceApplication.class)
@ActiveProfiles("memory-service")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:evolution;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "memory.service.security.internal-key=test-key",
        "memory.service.security.runner-key=runner-secret",
        "memory.service.security.router-key=router-secret",
        "memory.evolution.enabled=true",
        "memory.evolution.minimum-samples=2",
        "memory.evolution.maximum-baseline-failure-rate=1.0",
        "memory.evolution.minimum-improvement=0.1",
        "memory.evolution.minimum-canary-samples=3",
        "memory.evolution.rollback-failure-rate=0.34"
})
class ProcedureEvolutionServiceTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ProcedureEvolutionService evolution;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM memory_evolution_canary_observations");
        jdbc.update("DELETE FROM memory_evolution_trials");
        jdbc.update("DELETE FROM memory_evolution_evidence");
        jdbc.update("DELETE FROM memory_evolution_proposals");
        jdbc.update("DELETE FROM agent_procedure_applications");
        jdbc.update("DELETE FROM memory_project_members");
        jdbc.update("DELETE FROM memory_project_authorizations");
        jdbc.update("DELETE FROM agent_procedures");
        jdbc.update("""
                INSERT INTO agent_procedures
                (id,tenant_id,scope_type,scope_id,name,description,preconditions_json,steps_json,
                 success_conditions_json,procedure_version,success_count,attempt_count,success_rate,status)
                VALUES (1,'tenant','USER','7','deploy','base desc','["pre"]','["base step"]',
                 '["ok"]',1,2,4,0.5,'ACTIVE')
                """);
        insertApplication("node-fail", "FAILURE");
        insertApplication("node-success", "SUCCESS");
    }

    @Test
    void candidateIsGeneratedFromServerStoredBaselineAndVerifiedFailureFact() {
        FailureEvidenceResponse created = evolution.acceptFailureEvidence(failure("node-fail", "recover by checking deployment health"));
        assertEquals("CANDIDATE_CREATED", created.status());
        assertNotNull(created.proposalId());
        assertNotNull(created.candidateHash());

        String candidateJson = jdbc.queryForObject("SELECT candidate_content_json FROM memory_evolution_proposals WHERE proposal_id=?",
                String.class, created.proposalId());
        assertTrue(candidateJson.contains("recover by checking deployment health"));
        assertTrue(candidateJson.contains("base step"));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> evolution.getCanaryCandidate("tenant", 7L, 1L, created.proposalId(), created.candidateHash()));
        assertEquals("memory-rule-generator-v1", jdbc.queryForObject(
                "SELECT generator_version FROM memory_evolution_proposals WHERE proposal_id=?", String.class, created.proposalId()));

        FailureEvidenceResponse duplicate = evolution.acceptFailureEvidence(failure("node-fail", "recover by checking deployment health"));
        assertEquals("DUPLICATE", duplicate.status());
        assertEquals(created.evidenceId(), duplicate.evidenceId());
        assertEquals(created.proposalId(), duplicate.proposalId());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM memory_evolution_proposals", Integer.class));
    }

    @Test
    void failureEvidenceMustMatchRecordedFailureAndOwnerVersion() {
        assertThrows(IllegalStateException.class,
                () -> evolution.acceptFailureEvidence(failure("node-success", "not a failure")));
        FailureEvidenceRequest otherTenant = new FailureEvidenceRequest("other-tenant", 7L, 1L, 1,
                "session", 4L, "node-fail", "FAILED", "deploy task", "resolution");
        assertThrows(org.springframework.dao.EmptyResultDataAccessException.class,
                () -> evolution.acceptFailureEvidence(otherTenant));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM memory_evolution_evidence", Integer.class));
    }

    @Test
    void untrustedValidationAndDecisionCannotChangeCandidateOrProcedure() {
        FailureEvidenceResponse created = evolution.acceptFailureEvidence(failure("node-fail", "check health"));
        assertThrows(IllegalStateException.class, () -> evolution.validate(created.proposalId(),
                new CandidateValidationRequest("tenant", 7L, created.proposalId(), created.candidateHash(),
                        "suite", "v1", "untrusted", java.util.List.of())));
        EvolutionResponse proposal = evolution.get("tenant", created.proposalId());
        assertEquals("VALIDATING", proposal.status());
        assertThrows(IllegalStateException.class, () -> evolution.decide(created.proposalId(),
                new EvolutionDecisionRequest("tenant", created.proposalId(), created.candidateHash(), "router-a", "invalid")));
        assertEquals(1, jdbc.queryForObject("SELECT procedure_version FROM agent_procedures WHERE id=1", Integer.class));
        assertEquals("[\"base step\"]", jdbc.queryForObject("SELECT steps_json FROM agent_procedures WHERE id=1", String.class));
    }

    @Test
    void trustedRunnerTrialsAreIdempotentAndValidateCandidateAfterThreshold() throws Exception {
        FailureEvidenceResponse created = evolution.acceptFailureEvidence(failure("node-fail", "check health"));
        String suiteId = "suite";
        String suiteVersion = "v1";
        List<CandidateValidationRequest.Trial> trials = List.of(
                new CandidateValidationRequest.Trial("sample-1", "BASELINE", false, 10L),
                new CandidateValidationRequest.Trial("sample-1", "CANDIDATE", true, 11L),
                new CandidateValidationRequest.Trial("sample-2", "BASELINE", false, 12L),
                new CandidateValidationRequest.Trial("sample-2", "CANDIDATE", true, 13L));
        String attestation = attestation(created.proposalId(), created.candidateHash(), suiteId, suiteVersion, trials);
        EvolutionResponse validated = evolution.validate(created.proposalId(), new CandidateValidationRequest(
                "tenant", 7L, created.proposalId(), created.candidateHash(), suiteId, suiteVersion, attestation, trials));
        assertEquals("VALIDATED", validated.status());
        assertEquals(2, validated.baselineSamples());
        assertEquals(2, validated.candidateSamples());
        evolution.validate(created.proposalId(), new CandidateValidationRequest(
                "tenant", 7L, created.proposalId(), created.candidateHash(), suiteId, suiteVersion, attestation, trials));
        assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM memory_evolution_trials", Integer.class));

        String routerId = "router-a";
        EvolutionResponse canary = evolution.decide(created.proposalId(),
                new EvolutionDecisionRequest("tenant", created.proposalId(), created.candidateHash(), routerId,
                        routerReadyAttestation(created.proposalId(), created.candidateHash(), routerId)));
        assertEquals("CANARY", canary.status());
        assertEquals(1, evolution.listCanaryCandidates("tenant", 7L).size());
        assertEquals(5, evolution.listCanaryCandidates("tenant", 7L).getFirst().canaryTargetPercent());
        assertTrue(evolution.listCanaryCandidates("tenant", 8L).isEmpty());
        jdbc.update("UPDATE agent_procedures SET procedure_version=2 WHERE id=1");
        assertTrue(evolution.listCanaryCandidates("tenant", 7L).isEmpty());
        jdbc.update("UPDATE agent_procedures SET procedure_version=1 WHERE id=1");
        EvolutionResponse candidateSnapshot = evolution.getCanaryCandidate(
                "tenant", 7L, 1L, created.proposalId(), created.candidateHash());
        assertEquals("CANARY", candidateSnapshot.status());
        assertTrue(candidateSnapshot.candidateContentJson().contains("check health"));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> evolution.getCanaryCandidate("tenant", 8L, 1L, created.proposalId(), created.candidateHash()));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> evolution.getCanaryCandidate("tenant", 7L, 2L, created.proposalId(), created.candidateHash()));
        List<Observation> observations = List.of(
                new Observation("base-1", "BASELINE", true),
                new Observation("base-2", "BASELINE", true),
                new Observation("base-3", "BASELINE", true),
                new Observation("candidate-1", "CANDIDATE", false),
                new Observation("candidate-2", "CANDIDATE", false),
                new Observation("candidate-3", "CANDIDATE", true));
        for (int i = 0; i < observations.size(); i++) {
            Observation item = observations.get(i);
            String routerAttestation = routerAttestation(created.proposalId(), created.candidateHash(),
                    item.id(), item.variant(), item.passed());
            EvolutionResponse observed = evolution.observeCanary(created.proposalId(),
                    new com.miniagent.memory.service.web.CanaryObservationRequest(
                            "tenant", created.proposalId(), created.candidateHash(), item.id(),
                            item.variant(), item.passed(), routerAttestation));
            if (i == observations.size() - 1) assertEquals("ROLLED_BACK", observed.status());
        }
        assertEquals(6, jdbc.queryForObject("SELECT COUNT(*) FROM memory_evolution_canary_observations", Integer.class));
        assertEquals(3, evolution.get("tenant", created.proposalId()).baselineSamples());
        assertEquals(3, evolution.get("tenant", created.proposalId()).candidateSamples());
        Observation last = observations.get(observations.size() - 1);
        evolution.observeCanary(created.proposalId(), new com.miniagent.memory.service.web.CanaryObservationRequest(
                "tenant", created.proposalId(), created.candidateHash(), last.id(), last.variant(), last.passed(),
                routerAttestation(created.proposalId(), created.candidateHash(), last.id(), last.variant(), last.passed())));
        assertEquals(6, jdbc.queryForObject("SELECT COUNT(*) FROM memory_evolution_canary_observations", Integer.class));

        String baselineObservationId = "base-1";
        String alteredObservationAttestation = routerAttestation(created.proposalId(), created.candidateHash(),
                baselineObservationId, "BASELINE", false);
        assertThrows(IllegalStateException.class, () -> evolution.observeCanary(created.proposalId(),
                new com.miniagent.memory.service.web.CanaryObservationRequest("tenant", created.proposalId(),
                        created.candidateHash(), baselineObservationId, "BASELINE", false, alteredObservationAttestation)));

        String observationId = "candidate-1";
        String changedVariantAttestation = routerAttestation(created.proposalId(), created.candidateHash(),
                observationId, "BASELINE", false);
        assertThrows(IllegalStateException.class, () -> evolution.observeCanary(created.proposalId(),
                new com.miniagent.memory.service.web.CanaryObservationRequest("tenant", created.proposalId(),
                        created.candidateHash(), observationId, "BASELINE", false, changedVariantAttestation)));
    }

    @Test
    void callerSuppliedCandidateContentIsRejected() {
        ProcedureEvolutionRequest request = new ProcedureEvolutionRequest("tenant", 7L, 1L, 1,
                "arbitrary", "client-generator", new CandidateProcedure("fake", java.util.List.of(),
                java.util.List.of("caller supplied"), java.util.List.of()), java.util.List.of("unverified"));
        assertThrows(IllegalStateException.class, () -> evolution.createCandidate(request));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM memory_evolution_proposals", Integer.class));
    }

    private String routerReadyAttestation(long proposalId, String candidateHash, String routerId) throws Exception {
        long issuedAt = Instant.now().getEpochSecond();
        String payload = String.join("|", "tenant", Long.toString(proposalId), candidateHash, "5", routerId, "READY");
        return issuedAt + "." + hmac("router-secret", issuedAt + "|" + payload);
    }

    private String routerAttestation(long proposalId, String candidateHash, String observationId,
                                     String variant, boolean passed) throws Exception {
        long issuedAt = Instant.now().getEpochSecond();
        String payload = String.join("|", "tenant", Long.toString(proposalId), candidateHash,
                observationId, variant, Boolean.toString(passed));
        return issuedAt + "." + hmac("router-secret", issuedAt + "|" + payload);
    }

    private record Observation(String id, String variant, boolean passed) { }

    private String attestation(long proposalId, String candidateHash, String suiteId, String suiteVersion,
                               List<CandidateValidationRequest.Trial> trials) throws Exception {
        String samples = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(trials);
        String digest = sha256(samples);
        long issuedAt = Instant.now().getEpochSecond();
        String payload = String.join("|", "tenant", Long.toString(proposalId), candidateHash,
                suiteId, suiteVersion, digest);
        return issuedAt + "." + hmac("runner-secret", issuedAt + "|" + payload);
    }

    private String hmac(String secret, String value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    }

    private String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private FailureEvidenceRequest failure(String nodeId, String resolution) {
        return new FailureEvidenceRequest("tenant", 7L, 1L, 1, "session", 4L, nodeId,
                "TOOL_FAILURE", "deploy task", resolution);
    }

    private void insertApplication(String nodeId, String result) {
        jdbc.update("""
                INSERT INTO agent_procedure_applications
                (tenant_id,user_id,session_id,task_id,node_id,procedure_id,procedure_version,action_id,result)
                VALUES ('tenant',7,'session',4,?,1,1,'deploy-action',?)
                """, nodeId, result);
    }
}
