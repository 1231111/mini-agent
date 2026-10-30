package com.miniagent.agent.memory.manager;

import com.miniagent.agent.memory.entity.*;
import com.miniagent.agent.memory.repository.*;
import com.miniagent.memory.SecurityScanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@ConditionalOnProperty(prefix = "agent.memory.service", name = "mode", havingValue = "remote")
public class FailureEvidenceOutboxService {
    private static final Logger log = LoggerFactory.getLogger(FailureEvidenceOutboxService.class);
    private static final int MAX_ATTEMPTS = 10;
    private final AgentFailureEvidenceOutboxRepository outbox;
    private final AgentProcedureApplicationRepository applications;
    private final AgentProcedureRepository procedures;
    private final ObjectProvider<RemoteProcedureSettlementPort> remote;
    private final PlatformTransactionManager transactionManager;

    public FailureEvidenceOutboxService(AgentFailureEvidenceOutboxRepository outbox,
            AgentProcedureApplicationRepository applications, AgentProcedureRepository procedures,
            ObjectProvider<RemoteProcedureSettlementPort> remote, PlatformTransactionManager transactionManager) {
        this.outbox = outbox; this.applications = applications; this.procedures = procedures;
        this.remote = remote; this.transactionManager = transactionManager;
    }

    /** Must join the Episode transaction. Ambiguous or mismatched execution facts are deliberately ignored. */
    @Transactional
    public void enqueue(AgentEpisodeEntity episode) {
        if (episode == null || episode.getId() == null || episode.getOutcome() != AgentEpisodeEntity.Outcome.FAILURE
                || blank(episode.getTenantId()) || blank(episode.getSessionId()) || blank(episode.getUserId())
                || blank(episode.getTaskId()) || blank(episode.getResolution())
                || SecurityScanner.scan(episode.getResolution()) != null
                || SecurityScanner.REDACTED.equals(episode.getResolution().trim())) return;
        Long userId, taskId;
        try { userId = Long.valueOf(episode.getUserId()); taskId = Long.valueOf(episode.getTaskId()); }
        catch (NumberFormatException invalid) { return; }
        List<AgentProcedureApplicationEntity> failed = applications
                .findByTenantIdAndSessionIdAndTaskIdAndUserIdAndResult(episode.getTenantId(), episode.getSessionId(),
                        taskId, userId, AgentProcedureApplicationEntity.Result.FAILURE);
        if (failed.size() != 1) return;
        AgentProcedureApplicationEntity application = failed.get(0);
        AgentProcedureEntity procedure = procedures.findById(application.getProcedureId()).orElse(null);
        if (procedure == null || !episode.getTenantId().equals(application.getTenantId())
                || !userId.equals(application.getUserId()) || !episode.getTenantId().equals(procedure.getTenantId())
                || procedure.getScopeType() != AgentMemoryEntryEntity.ScopeType.USER
                || !String.valueOf(userId).equals(procedure.getScopeId())
                || !application.getProcedureVersion().equals(procedure.getProcedureVersion())) return;
        if (outbox.findByTenantIdAndUserIdAndSessionIdAndTaskIdAndNodeIdAndProcedureIdAndProcedureVersion(
                episode.getTenantId(), userId, episode.getSessionId(), taskId, application.getNodeId(),
                application.getProcedureId(), application.getProcedureVersion()).isPresent()) return;
        AgentFailureEvidenceOutboxEntity item = new AgentFailureEvidenceOutboxEntity();
        item.setTenantId(episode.getTenantId()); item.setUserId(userId); item.setSessionId(episode.getSessionId());
        item.setTaskId(taskId); item.setNodeId(application.getNodeId()); item.setProcedureId(application.getProcedureId());
        item.setProcedureVersion(application.getProcedureVersion());
        item.setFailureCode(episode.getFailureCode() == null || episode.getFailureCode().isBlank()
                ? "TASK_FAILURE" : episode.getFailureCode());
        item.setTaskSummary(episode.getTaskSummary()); item.setResolution(episode.getResolution().trim());
        item.setStatus(AgentFailureEvidenceOutboxEntity.Status.PENDING);
        item.setNextAttemptAt(LocalDateTime.now()); outbox.save(item);
    }

    @Scheduled(fixedDelayString = "${agent.memory.procedure.evolution-outbox.interval-ms:5000}")
    public void drain() {
        RemoteProcedureSettlementPort client = remote.getIfAvailable();
        if (client == null) return;
        LocalDateTime now = LocalDateTime.now();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> outbox.recoverExpired(AgentFailureEvidenceOutboxEntity.Status.PROCESSING,
                AgentFailureEvidenceOutboxEntity.Status.PENDING, now));
        List<Long> ids = outbox.findTop50ByStatusAndNextAttemptAtLessThanEqualOrderByIdAsc(
                AgentFailureEvidenceOutboxEntity.Status.PENDING, now).stream()
                .map(AgentFailureEvidenceOutboxEntity::getId).toList();
        for (Long id : ids) {
            String claimToken = UUID.randomUUID().toString();
            AgentFailureEvidenceOutboxEntity claimed = tx.execute(status -> {
                LocalDateTime claimTime = LocalDateTime.now();
                int count = outbox.claim(id, AgentFailureEvidenceOutboxEntity.Status.PENDING,
                        AgentFailureEvidenceOutboxEntity.Status.PROCESSING, claimTime, claimTime.plusMinutes(2), claimToken);
                return count == 1 ? outbox.findById(id).orElse(null) : null;
            });
            if (claimed == null) continue;
            deliver(claimed, claimToken, client);
        }
    }

    private void deliver(AgentFailureEvidenceOutboxEntity item, String claimToken,
                         RemoteProcedureSettlementPort client) {
        try {
            client.submitFailureEvidence(new RemoteProcedureSettlementPort.FailureEvidenceRequest(
                    item.getTenantId(), item.getUserId(), item.getProcedureId(), item.getProcedureVersion(),
                    item.getSessionId(), item.getTaskId(), item.getNodeId(), item.getFailureCode(),
                    item.getTaskSummary(), item.getResolution()));
            new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                    outbox.complete(item.getId(), AgentFailureEvidenceOutboxEntity.Status.PROCESSING,
                            AgentFailureEvidenceOutboxEntity.Status.DONE, claimToken));
        } catch (Exception e) {
            int attempts = item.getAttempts() + 1;
            AgentFailureEvidenceOutboxEntity.Status nextStatus = attempts >= MAX_ATTEMPTS
                    ? AgentFailureEvidenceOutboxEntity.Status.FAILED : AgentFailureEvidenceOutboxEntity.Status.PENDING;
            LocalDateTime nextAttempt = nextStatus == AgentFailureEvidenceOutboxEntity.Status.PENDING
                    ? LocalDateTime.now().plusSeconds(Math.min(3600L, 1L << Math.min(12, attempts)))
                    : LocalDateTime.now();
            new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                    outbox.failAttempt(item.getId(), AgentFailureEvidenceOutboxEntity.Status.PROCESSING,
                            nextStatus, attempts, nextAttempt, abbreviate(e.getMessage(), 1000), claimToken));
            log.warn("Failure evidence delivery failed: outboxId={}, attempt={}, type={}",
                    item.getId(), attempts, e.getClass().getSimpleName());
        }
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String abbreviate(String value, int max) {
        if (value == null) return ""; return value.length() <= max ? value : value.substring(0, max);
    }
}
