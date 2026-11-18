package com.miniagent.config.cloud;

import com.miniagent.agent.core.TokenUsageTracker;
import com.miniagent.config.entity.MembershipUsageOutbox;
import com.miniagent.config.entity.User;
import com.miniagent.config.repository.MembershipUsageOutboxRepository;
import com.miniagent.config.repository.UserRepository;
import com.miniagent.config.service.DatabaseConversationStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/** Persists usage events locally before retryable delivery to the account service. */
@Service
public class MembershipUsageOutboxService {
    private static final Logger log = LoggerFactory.getLogger(MembershipUsageOutboxService.class);
    private static final int MAX_ERROR_LENGTH = 1000;
    private static final int BATCH_SIZE = 100;

    private final MembershipUsageOutboxRepository outbox;
    private final DatabaseConversationStore conversations;
    private final UserRepository users;
    private final CloudMembershipClient membership;
    private final PlatformTransactionManager transactions;

    @Value("${agent.auth.cloud.membership.usage-outbox.lease-seconds:120}")
    private long leaseSeconds;

    public MembershipUsageOutboxService(MembershipUsageOutboxRepository outbox,
                                        DatabaseConversationStore conversations,
                                        UserRepository users,
                                        CloudMembershipClient membership,
                                        PlatformTransactionManager transactions) {
        this.outbox = outbox;
        this.conversations = conversations;
        this.users = users;
        this.membership = membership;
        this.transactions = transactions;
    }

    /** Called synchronously by the completed-call listener; no remote request occurs here. */
    public void enqueue(TokenUsageTracker.LlmCallUsage usage) {
        if (usage == null || usage.eventId() == null || usage.sessionId() == null
                || !membership.available()) return;
        try {
            Long localUserId = usage.userId() != null ? usage.userId()
                    : conversations.findUserIdBySession(usage.sessionId()).orElse(null);
            User user = localUserId == null ? null : users.findById(localUserId).orElse(null);
            if (user == null || user.getExternalId() == null || user.getExternalId().isBlank()) {
                log.warn("Usage event has no account identity; session={} event={}",
                        usage.sessionId(), usage.eventId());
                return;
            }
            long cloudUserId = Long.parseLong(user.getExternalId().trim());
            if (cloudUserId <= 0) throw new IllegalArgumentException("invalid account user id");
            if (outbox.findByEventId(usage.eventId()).isPresent()) return;

            MembershipUsageOutbox row = new MembershipUsageOutbox();
            row.setEventId(usage.eventId());
            row.setCloudUserId(cloudUserId);
            row.setInputTokens(Math.max(0, usage.inputTokens()));
            row.setOutputTokens(Math.max(0, usage.outputTokens()));
            row.setStatus(MembershipUsageOutbox.Status.PENDING);
            row.setNextAttemptAt(LocalDateTime.now());
            outbox.saveAndFlush(row);
        } catch (DataIntegrityViolationException duplicate) {
            log.debug("Usage event was already queued: {}", usage.eventId());
        } catch (RuntimeException failure) {
            // The quota debit is authoritative and already persisted. Keep this visible for
            // operations; transient delivery is retried by the outbox once persisted.
            log.error("Could not persist account usage event {} for session {}",
                    usage.eventId(), usage.sessionId(), failure);
        }
    }

    @Scheduled(fixedDelayString = "${agent.auth.cloud.membership.usage-outbox.interval-ms:5000}")
    public void drain() {
        if (!membership.available()) return;
        TransactionTemplate tx = new TransactionTemplate(transactions);
        LocalDateTime now = utcNow();
        tx.executeWithoutResult(status -> outbox.recoverExpired(
                MembershipUsageOutbox.Status.PROCESSING, MembershipUsageOutbox.Status.PENDING, now));
        List<Long> ids = outbox.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByIdAsc(
                        MembershipUsageOutbox.Status.PENDING, now).stream()
                .limit(BATCH_SIZE).map(MembershipUsageOutbox::getId).toList();
        for (Long id : ids) {
            Claimed claimed = claim(tx, id);
            if (claimed == null) continue;
            try {
                MembershipUsageOutbox row = claimed.row();
                membership.reportUsage(row.getEventId(), row.getCloudUserId(), row.getInputTokens(),
                        row.getOutputTokens(), 1);
                tx.executeWithoutResult(status -> outbox.complete(row.getId(),
                        MembershipUsageOutbox.Status.PROCESSING,
                        MembershipUsageOutbox.Status.DONE, claimed.token()));
            } catch (RuntimeException failure) {
                fail(tx, claimed, failure.getMessage());
            }
        }
    }

    private Claimed claim(TransactionTemplate tx, Long id) {
        String claimToken = UUID.randomUUID().toString();
        return tx.execute(status -> {
            LocalDateTime now = utcNow();
            if (outbox.claim(id, MembershipUsageOutbox.Status.PENDING,
                    MembershipUsageOutbox.Status.PROCESSING, now,
                    now.plusSeconds(Math.max(30, leaseSeconds)), claimToken) != 1) return null;
            return outbox.findById(id).map(row -> new Claimed(row, claimToken)).orElse(null);
        });
    }

    private void fail(TransactionTemplate tx, Claimed claimed, String message) {
        tx.executeWithoutResult(status -> {
            int attempts = claimed.row().getAttempts() == Integer.MAX_VALUE
                    ? Integer.MAX_VALUE : claimed.row().getAttempts() + 1;
            // Usage is part of billing/accounting history. Keep retrying with capped
            // backoff instead of silently making an event permanently undeliverable.
            MembershipUsageOutbox.Status next = MembershipUsageOutbox.Status.PENDING;
            long delay = Math.min(3600, 1L << Math.min(attempts, 11));
            String error = message == null ? "account usage delivery failed"
                    : message.substring(0, Math.min(MAX_ERROR_LENGTH, message.length()));
            outbox.failAttempt(claimed.row().getId(), MembershipUsageOutbox.Status.PROCESSING,
                    next, attempts, utcNow().plusSeconds(delay), error, claimed.token());
        });
        log.warn("Account usage event delivery failed event={} attempt={} (will retry): {}",
                claimed.row().getEventId(), attemptsForLog(claimed.row().getAttempts()), message);
    }

    private static LocalDateTime utcNow() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private static int attemptsForLog(int attempts) {
        return attempts == Integer.MAX_VALUE ? attempts : attempts + 1;
    }

    private record Claimed(MembershipUsageOutbox row, String token) {}
}
