package com.miniagent.application;

import com.miniagent.agent.memory.ChatMemoryConfig;
import com.miniagent.agent.memory.manager.CloudMemoryClient;
import com.miniagent.config.repository.UserRepository;
import com.miniagent.config.service.FileStorageService;
import com.miniagent.memory.MemoryStore;
import com.miniagent.common.context.OwnerContext;
import com.miniagent.memory.contract.MemoryApi.SubjectErasureResult;
import com.miniagent.agent.core.ExecutionControl;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * Owner-scoped, retryable erasure coordinator. Local SQL/blob/files are cleared
 * before a remote receipt can make the operation COMPLETE. A failed remote call
 * intentionally remains PENDING_REMOTE and is never reported as success.
 */
@Service
public class SubjectErasureService {
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
    private final TransactionTemplate tx;

    public SubjectErasureService(JdbcTemplate jdbc, MemoryStore memoryStore,
                                 FileStorageService files, UserRepository users,
                                 ObjectProvider<CloudMemoryClient> cloud,
                                 ChatMemoryConfig chatMemory,
                                 PlatformTransactionManager transactions,
                                 ObjectProvider<ExecutionControl> execution) {
        this.jdbc = jdbc;
        this.memoryStore = memoryStore;
        this.files = files;
        this.users = users;
        this.cloud = cloud;
        this.chatMemory = chatMemory;
        this.tx = new TransactionTemplate(transactions);
        this.execution = execution;
    }

    /** Only the authenticated caller's local user id may reach this method. */
    public Result erase(long userId, String tenantId) {
        if (userId <= 0 || tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("owner and tenant are required");
        }
        State state = state(userId, tenantId);
        if (state != null && Status.COMPLETE.name().equals(state.status)) {
            return new Result(Status.COMPLETE, true, true);
        }
        if (state == null || !Status.PENDING_REMOTE.name().equals(state.status)) {
            tx.executeWithoutResult(ignored -> eraseLocalInternal(userId, tenantId));
        }

        CloudMemoryClient remote = cloud.getIfAvailable();
        if (remote == null) {
            markComplete(userId, tenantId, null);
            return new Result(Status.COMPLETE, true, true);
        }
        try {
            SubjectErasureResult receipt;
            try (OwnerContext.Scope ignored = OwnerContext.bind(
                    new OwnerContext(userId, tenantId))) {
                receipt = remote.eraseSubject();
            }
            if (receipt == null || !receipt.complete()) {
                markRemoteFailure(userId, tenantId, "remote erasure did not return complete receipt");
                return new Result(Status.PENDING_REMOTE, true, false);
            }
            markComplete(userId, tenantId, Instant.now());
            return new Result(Status.COMPLETE, true, true);
        } catch (RuntimeException failure) {
            markRemoteFailure(userId, tenantId, safe(failure.getMessage()));
            return new Result(Status.PENDING_REMOTE, true, false);
        }
    }

    /** Reject writes after an authenticated subject has been erased. */
    public void assertWritable(String tenantId, long userId) {
        if (userId <= 0 || tenantId == null || tenantId.isBlank()) return;
        Integer erased = jdbc.query("SELECT COUNT(*) FROM memory_subject_erasure"
                        + " WHERE tenant_id=? AND user_id=? AND status='COMPLETE'",
                rs -> rs.next() ? rs.getInt(1) : 0, tenantId, userId);
        if (erased != null && erased > 0) {
            throw new IllegalStateException("subject memory has been erased; re-provision the account before writing");
        }
    }

    private void eraseLocalInternal(long userId, String tenantId) {
        State existing = state(userId, tenantId);
        if (existing != null && Status.PENDING_REMOTE.name().equals(existing.status)) return;
        try {
            jdbc.update("INSERT INTO memory_subject_erasure(tenant_id,user_id,subject_hash,status,updated_at)"
                            + " VALUES(?,?,?,'PENDING_LOCAL',CURRENT_TIMESTAMP(6))",
                    tenantId, userId, subjectHash(tenantId, userId));
        } catch (org.springframework.dao.DuplicateKeyException alreadyExists) {
            jdbc.update("UPDATE memory_subject_erasure SET status=CASE WHEN status='COMPLETE'"
                            + " THEN status ELSE 'PENDING_LOCAL' END, updated_at=CURRENT_TIMESTAMP(6),"
                            + " last_error=NULL WHERE tenant_id=? AND user_id=?", tenantId, userId);
        }

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
        jdbc.update("DELETE FROM agent_memory_outcome_outbox WHERE tenant_id=? AND payload_json LIKE ?",
                tenantId, "%\"userId\":" + userId + "%");
        deleteIn("agent_memory_index_outbox", "memory_id", entryIds);
        deleteIn("agent_memory_embeddings", "memory_id", entryIds);
        deleteIn("agent_memory_embeddings", "memory_id", episodeIds);
        deleteIn("agent_memory_entries", "id", entryIds);
        jdbc.update("DELETE FROM agent_project_members WHERE tenant_id=? AND user_id=?", tenantId, userId);
        jdbc.update("DELETE FROM user_daily_usage WHERE user_id=?", userId);

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

        // Durable blob/vector/files are part of the local phase. Any failure aborts
        // the transaction and leaves the receipt retryable.
        memoryStore.eraseUser(userId);
        files.eraseUserUploads(userId);
        jdbc.update("UPDATE memory_subject_erasure SET status='PENDING_REMOTE',local_completed_at=CURRENT_TIMESTAMP(6),"
                + "updated_at=CURRENT_TIMESTAMP(6) WHERE tenant_id=? AND user_id=?", tenantId, userId);
    }

    private State state(long userId, String tenantId) {
        return jdbc.query("SELECT status FROM memory_subject_erasure WHERE tenant_id=? AND user_id=?",
                rs -> rs.next() ? new State(rs.getString(1)) : null, tenantId, userId);
    }
    private record State(String status) {}
    private void markComplete(long userId, String tenantId, Instant remoteAt) {
        jdbc.update("UPDATE memory_subject_erasure SET status='COMPLETE',remote_completed_at=?,"
                + "updated_at=CURRENT_TIMESTAMP(6),last_error=NULL WHERE tenant_id=? AND user_id=?",
                remoteAt == null ? java.sql.Timestamp.from(Instant.now()) : java.sql.Timestamp.from(remoteAt),
                tenantId, userId);
    }
    private void markRemoteFailure(long userId, String tenantId, String error) {
        jdbc.update("UPDATE memory_subject_erasure SET status='PENDING_REMOTE',last_error=?,"
                + "updated_at=CURRENT_TIMESTAMP(6) WHERE tenant_id=? AND user_id=?", error, tenantId, userId);
    }
    private List<Long> ids(String sql, Object... args) { return jdbc.queryForList(sql, Long.class, args); }
    private void deleteIn(String table, String column, List<Long> ids) {
        if (ids.isEmpty()) return;
        String marks = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        jdbc.update("DELETE FROM " + table + " WHERE " + column + " IN (" + marks + ")", ids.toArray());
    }
    private static String safe(String message) { return message == null ? "remote erasure failed" : message.substring(0, Math.min(900, message.length())); }
    private static String subjectHash(String tenantId, long userId) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((tenantId + ":" + userId).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required", impossible);
        }
    }
}
