package com.miniagent.agent.memory.manager;

import com.miniagent.agent.memory.entity.AgentEpisodeEntity;
import com.miniagent.agent.memory.entity.FailureLearningOutboxEntity;
import com.miniagent.agent.memory.entity.FailureLearningOutboxEntity.Status;
import com.miniagent.agent.memory.repository.AgentEpisodeRepository;
import com.miniagent.agent.memory.repository.FailureLearningOutboxRepository;
import com.miniagent.common.context.OwnerContext;
import com.miniagent.application.SubjectErasureService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/** At-least-once trigger, idempotent proposal insertion, no automatic content acceptance. */
@Service
public class FailureLearningOutboxService {
    private static final Logger LOG = LoggerFactory.getLogger(FailureLearningOutboxService.class);
    private static final int MAX_ATTEMPTS = 12;
    private final FailureLearningOutboxRepository outbox;
    private final AgentEpisodeRepository episodes;
    private final ProcedureRewriteProposalService proposals;
    private final PlatformTransactionManager transactions;
    private final SubjectErasureService erasure;
    private final Clock clock;

    @Autowired
    public FailureLearningOutboxService(FailureLearningOutboxRepository outbox, AgentEpisodeRepository episodes,
            ProcedureRewriteProposalService proposals, PlatformTransactionManager transactions, SubjectErasureService erasure) {
        this(outbox, episodes, proposals, transactions, erasure, Clock.systemUTC());
    }

    FailureLearningOutboxService(FailureLearningOutboxRepository outbox, AgentEpisodeRepository episodes,
            ProcedureRewriteProposalService proposals, PlatformTransactionManager transactions,
            SubjectErasureService erasure, Clock clock) {
        this.outbox = outbox; this.episodes = episodes; this.proposals = proposals;
        this.transactions = transactions; this.erasure = erasure; this.clock = clock;
    }

    /** Must participate in the episode commit; a queue failure rolls the episode transaction back. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(AgentEpisodeEntity episode) {
        if (episode == null || episode.getOutcome() != AgentEpisodeEntity.Outcome.FAILURE || episode.getId() == null
                || episode.getTenantId() == null || episode.getTenantId().isBlank()
                || episode.getTaskId() == null || episode.getTaskId().isBlank()) return;
        Long user;
        try { user = Long.valueOf(episode.getUserId()); }
        catch (RuntimeException invalid) { return; }
        if (user <= 0) return;
        String hash = fingerprint(episode);
        if (outbox.existsByEpisodeIdAndSourceHash(episode.getId(), hash)) return;
        FailureLearningOutboxEntity row = new FailureLearningOutboxEntity();
        row.setEpisodeId(episode.getId()); row.setSourceHash(hash);
        row.setTenantId(episode.getTenantId()); row.setUserId(user); row.setNextAttemptAt(now());
        outbox.saveAndFlush(row);
    }

    @Scheduled(fixedDelayString = "${agent.memory.procedure.failure-learning-interval-ms:5000}")
    public void drain() {
        TransactionTemplate tx = new TransactionTemplate(transactions);
        tx.executeWithoutResult(ignored -> outbox.recoverExpired(Status.PROCESSING, Status.PENDING, now()));
        for (FailureLearningOutboxEntity due : outbox.findTop50ByStatusAndNextAttemptAtLessThanEqualOrderByIdAsc(Status.PENDING, now())) {
            String token = UUID.randomUUID().toString();
            FailureLearningOutboxEntity row = tx.execute(ignored -> {
                if (outbox.claim(due.getId(), Status.PENDING, Status.PROCESSING, token, now().plusMinutes(2), now()) != 1) return null;
                return outbox.findById(due.getId()).orElse(null);
            });
            if (row == null) continue;
            try {
                tx.executeWithoutResult(ignored -> {
                    try (var owner = OwnerContext.bind(new OwnerContext(row.getUserId(), row.getTenantId()))) {
                        if (erasure != null) erasure.assertWritable(row.getTenantId(), row.getUserId());
                        AgentEpisodeEntity episode = episodes.findByIdAndTenantIdAndUserId(row.getEpisodeId(),
                                row.getTenantId(), String.valueOf(row.getUserId())).orElse(null);
                        if (episode != null && fingerprint(episode).equals(row.getSourceHash()))
                            proposals.proposeFromFailureEpisode(episode);
                    }
                });
                // Missing, replaced or erased source is a safe no-op, never a reason to resurrect it.
                tx.executeWithoutResult(ignored -> outbox.finish(row.getId(), Status.PROCESSING, token,
                        Status.DONE, row.getAttempts(), now(), null));
            } catch (RuntimeException failure) {
                int attempts = row.getAttempts() + 1;
                Status next = attempts >= MAX_ATTEMPTS ? Status.FAILED : Status.PENDING;
                LocalDateTime dueAt = now().plusSeconds(Math.min(3600, 1L << Math.min(attempts, 11)));
                // Never persist episode content or provider/credential error details in queue diagnostics.
                String diagnostic = failure.getClass().getSimpleName();
                tx.executeWithoutResult(ignored -> outbox.finish(row.getId(), Status.PROCESSING, token,
                        next, attempts, dueAt, diagnostic));
                LOG.warn("Failure learning delivery deferred: episode={} attempt={} status={}", row.getEpisodeId(), attempts, next);
            }
        }
    }

    private LocalDateTime now() { return LocalDateTime.now(clock); }
    static String fingerprint(AgentEpisodeEntity episode) {
        try {
            String source = String.join("\u0000", Objects.toString(episode.getTenantId(), ""),
                    Objects.toString(episode.getUserId(), ""), Objects.toString(episode.getSessionId(), ""),
                    Objects.toString(episode.getTaskId(), ""), Objects.toString(episode.getOutcome(), ""),
                    Objects.toString(episode.getResolution(), ""));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) { throw new IllegalStateException("SHA-256 unavailable", impossible); }
    }
}
