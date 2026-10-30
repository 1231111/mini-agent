package com.miniagent.memory.service.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.memory.SecurityScanner;
import com.miniagent.memory.service.evolution.CandidateProcedure;
import com.miniagent.memory.service.evolution.EvolutionProperties;
import com.miniagent.memory.service.web.CanaryObservationRequest;
import com.miniagent.memory.service.web.CandidateValidationRequest;
import com.miniagent.memory.service.web.EvolutionDecisionRequest;
import com.miniagent.memory.service.web.EvolutionResponse;
import com.miniagent.memory.service.web.FailureEvidenceRequest;
import com.miniagent.memory.service.web.FailureEvidenceResponse;
import com.miniagent.memory.service.web.ProcedureEvolutionRequest;
import com.miniagent.memory.service.security.MemoryServiceSecurityProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.time.Instant;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

@Service
public class ProcedureEvolutionService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final EvolutionProperties properties;
    private final MemoryServiceSecurityProperties security;

    public ProcedureEvolutionService(JdbcTemplate jdbc, ObjectMapper mapper, EvolutionProperties properties,
                                     MemoryServiceSecurityProperties security) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.properties = properties;
        this.security = security;
    }

    @Transactional
    public FailureEvidenceResponse acceptFailureEvidence(FailureEvidenceRequest request) {
        requireEnabled();
        if (request.taskId() < 0) throw new IllegalArgumentException("taskId must not be negative");
        String resolution = request.resolution().trim();
        if (resolution.isEmpty() || SecurityScanner.scan(resolution) != null
                || SecurityScanner.REDACTED.equals(resolution))
            throw new IllegalArgumentException("failure resolution is empty or unsafe");
        ProcedureRow base = lockProcedure(request.tenantId(), request.userId(), request.procedureId());
        if (base.version != request.procedureVersion() || !"ACTIVE".equals(base.status))
            throw new IllegalStateException("failure evidence references a stale or inactive Procedure");
        Integer matchingFailures = jdbc.queryForObject("""
                SELECT COUNT(*) FROM agent_procedure_applications
                WHERE tenant_id=? AND user_id=? AND session_id=? AND task_id=? AND node_id=?
                  AND procedure_id=? AND procedure_version=? AND result='FAILURE'
                """, Integer.class, request.tenantId(), request.userId(), request.sessionId(),
                request.taskId(), request.nodeId(), request.procedureId(), request.procedureVersion());
        if (matchingFailures == null || matchingFailures != 1)
            throw new IllegalStateException("failure evidence must match one recorded failed Procedure application");

        String evidenceHash = sha256(String.join("|", request.tenantId(), request.userId().toString(),
                request.procedureId().toString(), request.procedureVersion().toString(), request.sessionId(),
                request.taskId().toString(), request.nodeId(), request.failureCode(), request.taskSummary(), resolution));
        jdbc.update("""
                INSERT INTO memory_evolution_evidence
                (tenant_id,user_id,procedure_id,procedure_version,session_id,task_id,node_id,failure_code,
                 task_summary,resolution_text,evidence_hash,created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP(6))
                ON DUPLICATE KEY UPDATE evidence_id=LAST_INSERT_ID(evidence_id)
                """, request.tenantId(), request.userId(), request.procedureId(), request.procedureVersion(),
                request.sessionId(), request.taskId(), request.nodeId(), request.failureCode(),
                request.taskSummary().trim(), resolution, evidenceHash);
        Long evidenceId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        Long existingProposal = jdbc.query("SELECT proposal_id FROM memory_evolution_evidence WHERE evidence_id=?",
                rs -> rs.next() ? (Long) rs.getObject(1) : null, evidenceId);
        if (existingProposal != null)
            return new FailureEvidenceResponse(evidenceId, existingProposal, "DUPLICATE", get(request.tenantId(), existingProposal).candidateHash());

        Integer sampleCount = jdbc.queryForObject("""
                SELECT COUNT(*) FROM agent_procedure_applications
                WHERE tenant_id=? AND user_id=? AND procedure_id=? AND procedure_version=?
                  AND result IN ('SUCCESS','FAILURE')
                """, Integer.class, request.tenantId(), request.userId(), request.procedureId(), request.procedureVersion());
        Integer failureCount = jdbc.queryForObject("""
                SELECT COUNT(*) FROM agent_procedure_applications
                WHERE tenant_id=? AND user_id=? AND procedure_id=? AND procedure_version=? AND result='FAILURE'
                """, Integer.class, request.tenantId(), request.userId(), request.procedureId(), request.procedureVersion());
        int samples = sampleCount == null ? 0 : sampleCount;
        double observedFailureRate = samples == 0 ? 1.0 : (failureCount == null ? 0.0 : failureCount) / (double) samples;
        if (samples < properties.minimumSamples()
                || observedFailureRate > properties.maximumBaselineFailureRate()) {
            return new FailureEvidenceResponse(evidenceId, null, "EVIDENCE_RECORDED", null);
        }
        CandidateProcedure candidate = new CandidateProcedure(base.description + " (updated from verified failure evidence)",
                parseList(base.preconditions), appendUnique(parseList(base.steps), resolution), parseList(base.successConditions));
        String candidateJson = write(candidate);
        scan(candidateJson);
        String candidateHash = sha256(candidateJson);
        String candidateId = "failure-" + evidenceId;
        jdbc.update("""
                INSERT INTO memory_evolution_proposals
                (tenant_id,user_id,procedure_id,base_version,candidate_id,base_content_json,candidate_content_json,
                 candidate_hash,generator_version,evidence_ids_json,status,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,'VALIDATING',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))
                """, request.tenantId(), request.userId(), request.procedureId(), base.version, candidateId,
                base.contentJson(mapper), candidateJson, candidateHash, "memory-rule-generator-v1",
                write(List.of(Long.toString(evidenceId))));
        Long proposalId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("UPDATE memory_evolution_evidence SET proposal_id=? WHERE evidence_id=? AND proposal_id IS NULL",
                proposalId, evidenceId);
        return new FailureEvidenceResponse(evidenceId, proposalId, "CANDIDATE_CREATED", candidateHash);
    }

    @Transactional
    public EvolutionResponse createCandidate(ProcedureEvolutionRequest request) {
        requireEnabled();
        throw new IllegalStateException("caller-supplied candidate content is disabled; submit verified failure evidence");
    }

    @Transactional
    public EvolutionResponse validate(long proposalId, CandidateValidationRequest request) {
        requireEnabled();
        ProposalRow proposal = lockProposal(request.tenantId(), request.userId(), proposalId);
        requireHash(proposal, request.candidateHash());
        if ("VALIDATED".equals(proposal.status)) return get(request.tenantId(), proposalId);
        if (!"VALIDATING".equals(proposal.status)) throw new IllegalStateException("proposal is not validating");
        verifyRunnerAttestation(proposal, request);
        if (request.trials().isEmpty()) {
            throw new IllegalArgumentException("validation runner must return at least one trial");
        }
        for (CandidateValidationRequest.Trial trial : request.trials()) {
            if (!"BASELINE".equals(trial.variant()) && !"CANDIDATE".equals(trial.variant())) {
                throw new IllegalArgumentException("trial variant must be BASELINE or CANDIDATE");
            }
            jdbc.update("""
                    INSERT INTO memory_evolution_trials
                    (tenant_id,proposal_id,candidate_hash,suite_id,suite_version,sample_id,variant,passed,duration_ms,created_at)
                    VALUES (?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP(6))
                    ON DUPLICATE KEY UPDATE passed=VALUES(passed),duration_ms=VALUES(duration_ms)
                    """, request.tenantId(), proposalId, proposal.candidateHash, request.suiteId(),
                    request.suiteVersion(), trial.sampleId(), trial.variant(), trial.passed(), trial.durationMillis());
        }
        TrialSummary summary = summarizeTrials(proposal, request.suiteId(), request.suiteVersion());
        jdbc.update("""
                UPDATE memory_evolution_proposals
                   SET baseline_samples=?,candidate_samples=?,baseline_failure_rate=?,candidate_failure_rate=?,updated_at=CURRENT_TIMESTAMP(6)
                 WHERE tenant_id=? AND proposal_id=? AND candidate_hash=? AND status='VALIDATING'
                """, summary.baselineSamples(), summary.candidateSamples(), summary.baselineFailureRate(),
                summary.candidateFailureRate(), request.tenantId(), proposalId, proposal.candidateHash);
        boolean enough = summary.baselineSamples() >= properties.minimumSamples()
                && summary.candidateSamples() >= properties.minimumSamples();
        boolean improved = summary.baselineFailureRate() - summary.candidateFailureRate()
                >= properties.minimumImprovement();
        if (enough && improved) {
            jdbc.update("UPDATE memory_evolution_proposals SET status='VALIDATED',decision_reason=?,updated_at=CURRENT_TIMESTAMP(6) "
                    + "WHERE tenant_id=? AND proposal_id=? AND candidate_hash=? AND status='VALIDATING'",
                    "runner validation passed", request.tenantId(), proposalId, proposal.candidateHash);
        }
        return get(request.tenantId(), proposalId);
    }

    @Transactional
    public EvolutionResponse decide(long proposalId, EvolutionDecisionRequest request) {
        requireEnabled();
        ProposalRow proposal = lockProposal(request.tenantId(), null, proposalId);
        requireHash(proposal, request.candidateHash());
        if (!"VALIDATED".equals(proposal.status)) throw new IllegalStateException("candidate is not validated");
        verifyRouterReady(proposal, request);
        jdbc.update("""
                UPDATE memory_evolution_proposals
                   SET status='CANARY', canary_target_percent=?, canary_started_at=CURRENT_TIMESTAMP(6),
                       decision_reason=?, updated_at=CURRENT_TIMESTAMP(6)
                 WHERE tenant_id=? AND proposal_id=? AND candidate_hash=? AND status='VALIDATED'
                """, properties.canaryPercent(), "validated candidate accepted by attested canary router",
                request.tenantId(), proposalId, proposal.candidateHash());
        return get(request.tenantId(), proposalId);
    }

    @Transactional
    public EvolutionResponse observeCanary(long proposalId, CanaryObservationRequest request) {
        requireEnabled();
        ProposalRow proposal = lockProposal(request.tenantId(), null, proposalId);
        requireHash(proposal, request.candidateHash());
        verifyRouterAttestation(proposal, request);
        if (!"CANARY".equals(proposal.status)) {
            if ("ROLLED_BACK".equals(proposal.status) && observationAlreadyRecorded(request)) {
                return get(request.tenantId(), proposalId);
            }
            throw new IllegalStateException("proposal is not in canary");
        }
        jdbc.update("""
                INSERT INTO memory_evolution_canary_observations
                (tenant_id,proposal_id,candidate_hash,source_observation_id,variant,passed,created_at)
                VALUES (?,?,?,?,?,?,CURRENT_TIMESTAMP(6))
                ON DUPLICATE KEY UPDATE source_observation_id=VALUES(source_observation_id)
                """, request.tenantId(), proposalId, proposal.candidateHash(), request.observationId(),
                request.variant(), request.passed());
        Object[] stored = jdbc.queryForObject("""
                SELECT variant,passed FROM memory_evolution_canary_observations
                 WHERE tenant_id=? AND proposal_id=? AND source_observation_id=?
                """, (rs, n) -> new Object[]{rs.getString(1), rs.getBoolean(2)},
                request.tenantId(), proposalId, request.observationId());
        if (!request.variant().equals(stored[0])
                || request.passed() != (Boolean) stored[1]) {
            throw new IllegalStateException("observation id was already used with different outcome");
        }
        CanarySummary summary = summarizeCanary(proposal);
        jdbc.update("UPDATE memory_evolution_proposals SET baseline_samples=?,candidate_samples=?,baseline_failure_rate=?,candidate_failure_rate=?,updated_at=CURRENT_TIMESTAMP(6) "
                + "WHERE tenant_id=? AND proposal_id=? AND candidate_hash=? AND status='CANARY'",
                summary.baselineSamples(), summary.candidateSamples(), summary.baselineFailureRate(),
                summary.candidateFailureRate(), request.tenantId(), proposalId, proposal.candidateHash());
        if (summary.baselineSamples() >= properties.minimumCanarySamples()
                && summary.candidateSamples() >= properties.minimumCanarySamples()
                && summary.candidateFailureRate() > properties.rollbackFailureRate()
                && summary.candidateFailureRate() > summary.baselineFailureRate()) {
            jdbc.update("UPDATE memory_evolution_proposals SET status='ROLLED_BACK',decision_reason=?,updated_at=CURRENT_TIMESTAMP(6) "
                    + "WHERE tenant_id=? AND proposal_id=? AND candidate_hash=? AND status='CANARY'",
                    "candidate canary failure rate exceeded rollback threshold and baseline", request.tenantId(), proposalId, proposal.candidateHash());
        }
        return get(request.tenantId(), proposalId);
    }

    @Transactional(readOnly = true)
    public EvolutionResponse get(String tenantId, long proposalId) {
        return jdbc.queryForObject("""
                SELECT proposal_id,status,candidate_hash,base_version,candidate_version,baseline_samples,candidate_samples,
                       decision_reason,tenant_id,user_id,procedure_id,canary_target_percent
                FROM memory_evolution_proposals WHERE tenant_id=? AND proposal_id=?
                """, (rs, n) -> new EvolutionResponse(rs.getLong(1), rs.getString(2), rs.getString(3),
                rs.getInt(4), (Integer) rs.getObject(5), rs.getInt(6), rs.getInt(7), rs.getString(8),
                rs.getString(9), rs.getLong(10), rs.getLong(11), null, rs.getInt(12)), tenantId, proposalId);
    }

    @Transactional(readOnly = true)
    public List<EvolutionResponse> listCanaryCandidates(String tenantId, long userId) {
        return jdbc.query("""
                SELECT p.proposal_id,p.status,p.candidate_hash,p.base_version,p.candidate_version,
                       p.baseline_samples,p.candidate_samples,p.decision_reason,p.tenant_id,p.user_id,
                       p.procedure_id,NULL AS candidate_content_json,p.canary_target_percent
                FROM memory_evolution_proposals p
                JOIN agent_procedures a ON a.tenant_id=p.tenant_id AND a.id=p.procedure_id
                    AND a.scope_type='USER' AND a.scope_id=CAST(p.user_id AS VARCHAR)
                    AND a.status='ACTIVE' AND a.procedure_version=p.base_version
                WHERE p.tenant_id=? AND p.user_id=? AND p.status='CANARY'
                ORDER BY p.procedure_id,p.proposal_id
                """, (rs, n) -> new EvolutionResponse(rs.getLong(1), rs.getString(2), rs.getString(3),
                rs.getInt(4), (Integer) rs.getObject(5), rs.getInt(6), rs.getInt(7), rs.getString(8),
                rs.getString(9), rs.getLong(10), rs.getLong(11), rs.getString(12), rs.getInt(13)),
                tenantId, userId);
    }

    @Transactional(readOnly = true)
    public EvolutionResponse getCanaryCandidate(String tenantId, long userId, long procedureId,
                                                 long proposalId, String candidateHash) {
        List<EvolutionResponse> matches = jdbc.query("""
                SELECT proposal_id,status,candidate_hash,base_version,candidate_version,baseline_samples,candidate_samples,
                       decision_reason,tenant_id,user_id,procedure_id,candidate_content_json,canary_target_percent
                FROM memory_evolution_proposals
                WHERE tenant_id=? AND user_id=? AND procedure_id=? AND proposal_id=?
                  AND candidate_hash=? AND status='CANARY'
                """, (rs, n) -> new EvolutionResponse(rs.getLong(1), rs.getString(2), rs.getString(3),
                rs.getInt(4), (Integer) rs.getObject(5), rs.getInt(6), rs.getInt(7), rs.getString(8),
                rs.getString(9), rs.getLong(10), rs.getLong(11), rs.getString(12), rs.getInt(13)),
                tenantId, userId, procedureId, proposalId, candidateHash);
        if (matches.isEmpty()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND, "active canary candidate not found");
        }
        return matches.getFirst();
    }

    private ProcedureRow lockProcedure(String tenantId, long userId, long procedureId) {
        return jdbc.queryForObject("""
                SELECT procedure_version,status,name,description,preconditions_json,steps_json,success_conditions_json
                FROM agent_procedures WHERE tenant_id=? AND id=? AND scope_type='USER' AND scope_id=? FOR UPDATE
                """, (rs, n) -> new ProcedureRow(rs.getInt(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7)), tenantId, procedureId, String.valueOf(userId));
    }

    private ProposalRow lockProposal(String tenantId, Long userId, long proposalId) {
        String sql = "SELECT proposal_id,user_id,procedure_id,base_version,candidate_hash,base_content_json,candidate_content_json,status,candidate_version FROM memory_evolution_proposals WHERE tenant_id=? AND proposal_id=?";
        sql += userId == null ? " FOR UPDATE" : " AND user_id=? FOR UPDATE";
        Object[] args = userId == null ? new Object[]{tenantId, proposalId} : new Object[]{tenantId, proposalId, userId};
        return jdbc.queryForObject(sql, (rs, n) -> new ProposalRow(tenantId, rs.getLong(1), rs.getLong(2),
                rs.getLong(3), rs.getInt(4), rs.getString(5), rs.getString(6), rs.getString(7),
                rs.getString(8), (Integer) rs.getObject(9)), args);
    }

    private void requireHash(ProposalRow row, String hash) {
        if (!row.candidateHash.equals(hash)) throw new IllegalArgumentException("candidate hash mismatch");
    }

    private void verifyRunnerAttestation(ProposalRow proposal, CandidateValidationRequest request) {
        if (!security.runnerConfigured()) {
            throw new IllegalStateException("trusted isolated validation runner execution is not connected; candidate promotion is fail-closed");
        }
        String sampleDigest = sha256(write(request.trials()));
        String payload = String.join("|", request.tenantId(), Long.toString(proposal.proposalId),
                proposal.candidateHash, request.suiteId(), request.suiteVersion(), sampleDigest);
        verifyAttestation(request.runnerAttestation(), payload, security.runnerKey());
    }

    private TrialSummary summarizeTrials(ProposalRow proposal, String suiteId, String suiteVersion) {
        return jdbc.query("""
                SELECT variant, COUNT(*), SUM(CASE WHEN passed=FALSE THEN 1 ELSE 0 END)
                  FROM memory_evolution_trials
                 WHERE tenant_id=? AND proposal_id=? AND candidate_hash=? AND suite_id=? AND suite_version=?
                 GROUP BY variant
                """, rs -> {
            int baseline = 0, candidate = 0, baselineFailures = 0, candidateFailures = 0;
            while (rs.next()) {
                int samples = rs.getInt(2), failures = rs.getInt(3);
                if ("BASELINE".equals(rs.getString(1))) { baseline = samples; baselineFailures = failures; }
                if ("CANDIDATE".equals(rs.getString(1))) { candidate = samples; candidateFailures = failures; }
            }
            return new TrialSummary(baseline, candidate,
                    baseline == 0 ? 1.0 : baselineFailures / (double) baseline,
                    candidate == 0 ? 1.0 : candidateFailures / (double) candidate);
        }, proposal.tenantId, proposal.proposalId, proposal.candidateHash, suiteId, suiteVersion);
    }

    private CanarySummary summarizeCanary(ProposalRow proposal) {
        return jdbc.query("""
                SELECT variant,COUNT(*),SUM(CASE WHEN passed=FALSE THEN 1 ELSE 0 END)
                  FROM memory_evolution_canary_observations
                 WHERE tenant_id=? AND proposal_id=? AND candidate_hash=?
                 GROUP BY variant
                """, rs -> {
            int baselineSamples = 0, candidateSamples = 0;
            int baselineFailures = 0, candidateFailures = 0;
            while (rs.next()) {
                int samples = rs.getInt(2);
                int failures = rs.getInt(3);
                if ("BASELINE".equals(rs.getString(1))) {
                    baselineSamples = samples;
                    baselineFailures = failures;
                } else if ("CANDIDATE".equals(rs.getString(1))) {
                    candidateSamples = samples;
                    candidateFailures = failures;
                }
            }
            return new CanarySummary(baselineSamples, candidateSamples,
                    baselineSamples == 0 ? 0.0 : baselineFailures / (double) baselineSamples,
                    candidateSamples == 0 ? 0.0 : candidateFailures / (double) candidateSamples);
        }, proposal.tenantId, proposal.proposalId, proposal.candidateHash);
    }

    private void verifyRouterReady(ProposalRow proposal, EvolutionDecisionRequest request) {
        if (!security.routerConfigured()) {
            throw new IllegalStateException("trusted canary traffic router is not configured; candidate promotion is fail-closed");
        }
        String[] parts = request.routerAttestation().split("\\.", -1);
        if (parts.length != 2) throw new IllegalStateException("invalid router readiness attestation format");
        String payload = String.join("|", request.tenantId(), Long.toString(proposal.proposalId),
                proposal.candidateHash, Integer.toString(properties.canaryPercent()), request.routerId(), "READY");
        verifyAttestation(request.routerAttestation(), payload, security.routerKey());
    }

    private boolean observationAlreadyRecorded(CanaryObservationRequest request) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT CASE WHEN COUNT(*)=1 THEN TRUE ELSE FALSE END
                  FROM memory_evolution_canary_observations
                 WHERE tenant_id=? AND proposal_id=? AND source_observation_id=?
                   AND variant=? AND passed=?
                """, Boolean.class, request.tenantId(), request.proposalId(), request.observationId(),
                request.variant(), request.passed()));
    }

    private void verifyRouterAttestation(ProposalRow proposal, CanaryObservationRequest request) {
        if (!security.routerConfigured()) {
            throw new IllegalStateException("trusted canary traffic router is not configured; candidate promotion is fail-closed");
        }
        String payload = String.join("|", request.tenantId(), Long.toString(proposal.proposalId),
                proposal.candidateHash, request.observationId(), request.variant(), Boolean.toString(request.passed()));
        verifyAttestation(request.routerAttestation(), payload, security.routerKey());
    }

    private void verifyAttestation(String attestation, String payload, String secret) {
        String[] parts = attestation.split("\\.", -1);
        if (parts.length != 2) throw new IllegalStateException("invalid attestation format");
        long issuedAt;
        try { issuedAt = Long.parseLong(parts[0]); }
        catch (NumberFormatException e) { throw new IllegalStateException("invalid attestation timestamp", e); }
        long now = Instant.now().getEpochSecond();
        if (Math.abs(now - issuedAt) > security.attestationMaxAgeSeconds())
            throw new IllegalStateException("attestation is expired");
        String expected = hmac(secret, issuedAt + "|" + payload);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                parts[1].getBytes(StandardCharsets.US_ASCII)))
            throw new IllegalStateException("attestation signature mismatch");
    }

    private String hmac(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (InvalidKeyException | java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("unable to verify attestation", e);
        }
    }

    private void requireEnabled() { if (!properties.enabled()) throw new IllegalStateException("memory evolution is disabled"); }
    private void scan(String content) { if (SecurityScanner.scan(content) != null) throw new IllegalArgumentException("candidate failed security scan"); }
    private String write(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("unable to serialize evolution data", e); }
    }

    private List<String> parseList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try { return mapper.readValue(json, mapper.getTypeFactory().constructCollectionType(List.class, String.class)); }
        catch (Exception e) { throw new IllegalStateException("invalid Procedure content JSON", e); }
    }

    private List<String> appendUnique(List<String> values, String value) {
        if (values.contains(value)) return values;
        java.util.ArrayList<String> updated = new java.util.ArrayList<>(values);
        updated.add(value);
        return List.copyOf(updated);
    }

    private String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    private record ProcedureRow(int version, String status, String name, String description,
                                String preconditions, String steps, String successConditions) {
        String contentJson(ObjectMapper mapper) {
            try { return mapper.writeValueAsString(Map.of("name", name == null ? "" : name,
                    "description", description == null ? "" : description,
                    "preconditions", json(mapper, preconditions), "steps", json(mapper, steps),
                    "successConditions", json(mapper, successConditions))); }
            catch (Exception e) { throw new IllegalStateException(e); }
        }
        private Object json(ObjectMapper mapper, String value) {
            if (value == null || value.isBlank()) return List.of();
            try { return mapper.readValue(value, List.class); }
            catch (Exception e) { throw new IllegalStateException("invalid Procedure snapshot JSON", e); }
        }
    }
    private record TrialSummary(int baselineSamples, int candidateSamples,
                                double baselineFailureRate, double candidateFailureRate) { }

    private record CanarySummary(int baselineSamples, int candidateSamples,
                                 double baselineFailureRate, double candidateFailureRate) { }

    private record ProposalRow(String tenantId, long proposalId, long userId, long procedureId, int baseVersion,
                               String candidateHash, String baseJson, String candidateJson, String status,
                               Integer candidateVersion) { }
}
