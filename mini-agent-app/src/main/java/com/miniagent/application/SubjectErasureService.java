package com.miniagent.application;

import com.miniagent.agent.core.ExecutionControl;
import com.miniagent.agent.memory.ChatMemoryConfig;
import com.miniagent.agent.memory.manager.CloudMemoryClient;
import com.miniagent.common.context.OwnerContext;
import com.miniagent.config.repository.UserRepository;
import com.miniagent.config.service.FileStorageService;
import com.miniagent.memory.MemoryStore;
import com.miniagent.memory.contract.MemoryApi.SubjectErasureResult;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.DependsOn;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Owner-scoped subject erasure. A row in {@code memory_subject_erasure} also acts
 * as the write gate: writers and erasure lock the same row in their transaction.
 * Remote deletion is retried from the durable PENDING_REMOTE state.
 */
@Service
@DependsOnDatabaseInitialization
@DependsOn("postJpaSchemaMigrationBarrier")
public class SubjectErasureService {
    private static final Logger log = LoggerFactory.getLogger(SubjectErasureService.class);
    private static final int REMOTE_BATCH_SIZE = 100;
    private static final Duration REMOTE_LEASE = Duration.ofMinutes(5);
    private static final long MAX_RETRY_DELAY_SECONDS = Duration.ofHours(6).toSeconds();

    public enum Status { PENDING_LOCAL, PENDING_REMOTE, COMPLETE }
    public record Result(Status status, boolean localComplete, boolean remoteComplete) {
        public boolean complete() { return status == Status.COMPLETE; }
    }

    private final JdbcTemplate jdbc;
    private final MemoryStore memoryStore;
    private final FileStorageService files;
    private final UserRepository users;
    private final ObjectProvider<CloudMemoryClient> cloud;
    private final ChatMemoryConfig chatMemory;
    private final ObjectProvider<ExecutionControl> execution;
    private final ObjectProvider<AutonomousGoalService> autonomousGoals;
    private final TransactionTemplate tx;
    private final AtomicLong pendingRemoteGauge = new AtomicLong();

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private MeterRegistry metrics;

    public SubjectErasureService(JdbcTemplate jdbc, MemoryStore memoryStore,
                                 FileStorageService files, UserRepository users,
                                 ObjectProvider<CloudMemoryClient> cloud,
                                 ChatMemoryConfig chatMemory,
                                 PlatformTransactionManager transactions,
                                 ObjectProvider<ExecutionControl> execution,
                                 ObjectProvider<AutonomousGoalService> autonomousGoals) {
        this.jdbc = jdbc;
        this.memoryStore = memoryStore;
        this.files = files;
        this.users = users;
        this.cloud = cloud;
        this.chatMemory = chatMemory;
        this.tx = new TransactionTemplate(transactions);
        this.execution = execution;
        this.autonomousGoals = autonomousGoals;
    }

    @PostConstruct
    void registerMetrics() {
        if (metrics != null) {
            metrics.gauge("agent_subject_erasure_pending_remote", pendingRemoteGauge);
            refreshPendingRemoteGauge();
        }
    }

    /** Only the authenticated caller's local user id may reach this method. */
    public Result erase(long userId, String tenantId) {
        validateOwner(userId, tenantId);
        Status afterLocal = tx.execute(ignored -> {
            ensureGateRow(userId, tenantId);
            String current = lockedStatus(userId, tenantId);
            if (Status.COMPLETE.name().equals(current)) return Status.COMPLETE;
            if (Status.PENDING_REMOTE.name().equals(current)) return Status.PENDING_REMOTE;
            eraseLocalInternal(userId, tenantId);
            return Status.PENDING_REMOTE;
        });
        if (afterLocal == Status.COMPLETE) return new Result(Status.COMPLETE, true, true);

        CloudMemoryClient remote = cloud.getIfAvailable();
        if (remote == null) {
            // A deployment without a remote memory service has no remote phase.
            markCompleteLocally(userId, tenantId);
            refreshPendingRemoteGauge();
            return new Result(Status.COMPLETE, true, true);
        }
        processRemote(userId, tenantId, remote);
        refreshPendingRemoteGauge();
        return currentResult(userId, tenantId);
    }

    /**
     * Checks the tombstone and locks it until the surrounding write transaction
     * commits. The controller's preflight call creates the row as well, while
     * each actual writer repeats this check inside its own transaction.
     */
    public void assertWritable(String tenantId, long userId) {
        if (userId <= 0 || tenantId == null || tenantId.isBlank()) return;
        Runnable check = () -> {
            ensureGateRow(userId, tenantId);
            String current = lockedStatus(userId, tenantId);
            if (current != null && !"ACTIVE".equals(current)) {
                throw new IllegalStateException(
                        "subject memory is being erased or has been erased; re-provision the account before writing");
            }
        };
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            check.run();
        } else {
            tx.executeWithoutResult(ignored -> check.run());
        }
    }

    /** Retry remote erasures even if the caller never retries the API request. */
    @Scheduled(fixedDelayString = "${agent.memory.subject-erasure.interval-ms:5000}")
    public void drainRemoteErasures() {
        CloudMemoryClient remote = cloud.getIfAvailable();
        if (remote == null) return;
        refreshPendingRemoteGauge();
        List<Owner> due = jdbc.query("SELECT tenant_id,user_id FROM memory_subject_erasure"
                        + " WHERE status='PENDING_REMOTE' AND next_attempt_at<=CURRENT_TIMESTAMP(6)"
                        + " AND (lease_until IS NULL OR lease_until<CURRENT_TIMESTAMP(6))"
                        + " ORDER BY next_attempt_at,id LIMIT ?",
                (rs, row) -> new Owner(rs.getString(1), rs.getLong(2)), REMOTE_BATCH_SIZE);
        for (Owner owner : due) {
            processRemote(owner.userId(), owner.tenantId(), remote);
        }
        refreshPendingRemoteGauge();
    }

    private void processRemote(long userId, String tenantId, CloudMemoryClient remote) {
        String token = claimRemote(userId, tenantId);
        if (token == null) return;
        try {
            SubjectErasureResult receipt;
            try (OwnerContext.Scope ignored = OwnerContext.bind(new OwnerContext(userId, tenantId))) {
                receipt = remote.eraseSubject();
            }
            if (receipt == null || !receipt.complete()) {
                markRemoteFailure(userId, tenantId, token,
                        "remote erasure did not return complete receipt");
                return;
            }
            completeRemote(userId, tenantId, token, Instant.now());
        } catch (RuntimeException failure) {
            markRemoteFailure(userId, tenantId, token, safe(failure.getMessage()));
        }
    }

    private String claimRemote(long userId, String tenantId) {
        String token = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();
        int claimed = jdbc.update("UPDATE memory_subject_erasure SET claim_token=?,lease_until=?"
                        + " WHERE tenant_id=? AND user_id=? AND status='PENDING_REMOTE'"
                        + " AND next_attempt_at<=? AND (lease_until IS NULL OR lease_until<?)",
                token, now.plus(REMOTE_LEASE), tenantId, userId, now, now);
        return claimed == 1 ? token : null;
    }

    private void completeRemote(long userId, String tenantId, String token, Instant remoteAt) {
        tx.executeWithoutResult(ignored -> jdbc.update("UPDATE memory_subject_erasure"
                        + " SET status='COMPLETE',remote_completed_at=?,updated_at=CURRENT_TIMESTAMP(6),"
                        + "last_error=NULL,claim_token=NULL,lease_until=NULL"
                        + " WHERE tenant_id=? AND user_id=? AND status='PENDING_REMOTE' AND claim_token=?",
                java.sql.Timestamp.from(remoteAt), tenantId, userId, token));
    }

    private void markRemoteFailure(long userId, String tenantId, String token, String error) {
        tx.executeWithoutResult(ignored -> {
            Integer attempts = jdbc.query("SELECT attempts FROM memory_subject_erasure"
                            + " WHERE tenant_id=? AND user_id=? AND status='PENDING_REMOTE' AND claim_token=?",
                    rs -> rs.next() ? rs.getInt(1) : null, tenantId, userId, token);
            if (attempts == null) return;
            int nextAttempt = Math.min(30, attempts + 1);
            long delay = Math.min(MAX_RETRY_DELAY_SECONDS, 1L << Math.min(nextAttempt, 14));
            jdbc.update("UPDATE memory_subject_erasure SET attempts=?,next_attempt_at=?,last_error=?"
                            + ",updated_at=CURRENT_TIMESTAMP(6),claim_token=NULL,lease_until=NULL"
                            + " WHERE tenant_id=? AND user_id=? AND status='PENDING_REMOTE' AND claim_token=?",
                    nextAttempt, LocalDateTime.now().plusSeconds(delay), error,
                    tenantId, userId, token);
            log.warn("Subject erasure remote phase remains pending tenant={} user={} attempts={}",
                    tenantId, userId, nextAttempt);
        });
    }

    private void markCompleteLocally(long userId, String tenantId) {
        tx.executeWithoutResult(ignored -> jdbc.update("UPDATE memory_subject_erasure"
                        + " SET status='COMPLETE',remote_completed_at=CURRENT_TIMESTAMP(6),"
                        + "updated_at=CURRENT_TIMESTAMP(6),last_error=NULL,claim_token=NULL,lease_until=NULL"
                        + " WHERE tenant_id=? AND user_id=? AND status='PENDING_REMOTE'",
                tenantId, userId));
    }

    private Result currentResult(long userId, String tenantId) {
        String status = jdbc.query("SELECT status FROM memory_subject_erasure WHERE tenant_id=? AND user_id=?",
                rs -> rs.next() ? rs.getString(1) : null, tenantId, userId);
        if (Status.COMPLETE.name().equals(status)) return new Result(Status.COMPLETE, true, true);
        return new Result(Status.PENDING_REMOTE, true, false);
    }

    private void refreshPendingRemoteGauge() {
        pendingRemoteGauge.set(jdbc.queryForObject(
                "SELECT COUNT(*) FROM memory_subject_erasure WHERE status='PENDING_REMOTE'", Long.class));
    }

    private void ensureGateRow(long userId, String tenantId) {
        try {
            jdbc.update("INSERT INTO memory_subject_erasure(tenant_id,user_id,subject_hash,status,"
                            + "updated_at,next_attempt_at,attempts) VALUES(?,?,?,'ACTIVE',CURRENT_TIMESTAMP(6),"
                            + "CURRENT_TIMESTAMP(6),0)", tenantId, userId, subjectHash(tenantId, userId));
        } catch (DuplicateKeyException concurrentCreation) {
            // The unique owner key arbitrates concurrent first writes; the row is locked next.
        }
    }

    private String lockedStatus(long userId, String tenantId) {
        return jdbc.query("SELECT status FROM memory_subject_erasure WHERE tenant_id=? AND user_id=? FOR UPDATE",
                rs -> rs.next() ? rs.getString(1) : null, tenantId, userId);
    }

    private void eraseLocalInternal(long userId, String tenantId) {
        jdbc.update("UPDATE memory_subject_erasure SET status='PENDING_LOCAL',updated_at=CURRENT_TIMESTAMP(6),"
                + "last_error=NULL WHERE tenant_id=? AND user_id=?", tenantId, userId);

        // Revoke both queued and in-flight autonomous continuations before
        // deleting their source conversations.  The queue is owner-scoped and
        // therefore must be part of subject erasure, otherwise a later scheduler
        // tick could resume a deleted goal with stale credentials.
        AutonomousGoalService goals = autonomousGoals.getIfAvailable();
        if (goals != null) goals.eraseOwner(userId, tenantId);

        List<Long> entryIds = ids("SELECT id FROM agent_memory_entries WHERE tenant_id=?"
                + " AND scope_type='USER' AND scope_id=?", tenantId, String.valueOf(userId));
        List<Long> episodeIds = ids("SELECT id FROM agent_episodes WHERE tenant_id=? AND user_id=?",
                tenantId, String.valueOf(userId));
        List<Long> procedureIds = ids("SELECT id FROM agent_procedures WHERE tenant_id=?"
                + " AND scope_type='USER' AND scope_id=?", tenantId, String.valueOf(userId));
        List<Long> proposalIds = ids("SELECT id FROM agent_procedure_rewrite_proposals WHERE tenant_id=? AND user_id=?",
                tenantId, userId);

        deleteIn("agent_procedure_candidate_validations", "proposal_id", proposalIds);
        deleteIn("agent_procedure_rewrite_proposals", "id", proposalIds);
        deleteIn("agent_procedure_applications", "procedure_id", procedureIds);
        deleteIn("agent_procedure_revisions", "procedure_id", procedureIds);
        deleteIn("agent_procedure_definition_outbox", "procedure_id", procedureIds);
        deleteIn("agent_procedures", "id", procedureIds);
        deleteIn("experience_cluster_members", "episode_id", episodeIds);
        jdbc.update("DELETE FROM experience_clusters WHERE tenant_id=? AND owner_user_id=?", tenantId, userId);
        jdbc.update("DELETE FROM agent_episodes WHERE tenant_id=? AND user_id=?", tenantId, String.valueOf(userId));
        jdbc.update("DELETE FROM agent_semantic_facts WHERE tenant_id=? AND scope_type='USER' AND scope_id=?",
                tenantId, String.valueOf(userId));
        jdbc.update("DELETE FROM agent_working_memories WHERE tenant_id=? AND user_id=?",
                tenantId, String.valueOf(userId));
        jdbc.update("DELETE FROM agent_events WHERE tenant_id=? AND actor=?", tenantId, "user:" + userId);
        jdbc.update("DELETE FROM agent_failure_evidence_outbox WHERE tenant_id=? AND user_id=?", tenantId, userId);
        jdbc.update("DELETE FROM agent_canary_assignment_outbox WHERE tenant_id=? AND user_id=?", tenantId, userId);
        jdbc.update("DELETE FROM agent_project_authorization_outbox WHERE tenant_id=? AND owner_user_id=?", tenantId, userId);
        jdbc.update("DELETE FROM agent_memory_outcome_outbox WHERE tenant_id=? AND user_id=?", tenantId, userId);
        deleteIn("agent_memory_index_outbox", "memory_id", entryIds);
        deleteIn("agent_memory_embeddings", "memory_id", entryIds);
        deleteIn("agent_memory_embeddings", "memory_id", episodeIds);
        deleteIn("agent_memory_entries", "id", entryIds);
        jdbc.update("DELETE FROM agent_project_members WHERE tenant_id=? AND user_id=?", tenantId, userId);

        List<String> sessions = jdbc.queryForList("SELECT id FROM chat_conversations WHERE user_id=?", String.class, userId);
        for (String session : sessions) {
            ExecutionControl control = execution.getIfAvailable();
            if (control != null) control.cancel(session);
            chatMemory.evict(session);
            jdbc.update("DELETE FROM agent_events WHERE tenant_id=? AND session_id=?", tenantId, session);
            jdbc.update("DELETE FROM agent_trace_steps WHERE session_id=?", session);
            jdbc.update("DELETE FROM chat_messages WHERE conversation_id=?", session);
            jdbc.update("DELETE FROM chat_tasks WHERE user_id=? AND session_id=?", userId, session);
        }
        jdbc.update("DELETE FROM chat_conversations WHERE user_id=?", userId);

        memoryStore.eraseUser(userId);
        files.eraseUserUploads(userId);
        jdbc.update("UPDATE memory_subject_erasure SET status='PENDING_REMOTE',"
                + "local_completed_at=CURRENT_TIMESTAMP(6),next_attempt_at=CURRENT_TIMESTAMP(6),"
                + "attempts=0,updated_at=CURRENT_TIMESTAMP(6) WHERE tenant_id=? AND user_id=?",
                tenantId, userId);
    }

    private record Owner(String tenantId, long userId) { }

    private static void validateOwner(long userId, String tenantId) {
        if (userId <= 0 || tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("owner and tenant are required");
        }
    }

    private List<Long> ids(String sql, Object... args) {
        return jdbc.queryForList(sql, Long.class, args);
    }

    private void deleteIn(String table, String column, List<Long> ids) {
        if (ids.isEmpty()) return;
        String marks = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        jdbc.update("DELETE FROM " + table + " WHERE " + column + " IN (" + marks + ")", ids.toArray());
    }

    private static String safe(String message) {
        return message == null ? "remote erasure failed" : message.substring(0, Math.min(900, message.length()));
    }

    private static String subjectHash(String tenantId, long userId) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((tenantId + ":" + userId).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required", impossible);
        }
    }
}
