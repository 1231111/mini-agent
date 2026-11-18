package com.miniagent.config.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.miniagent.agent.execution.ActionExecutionStatus;
import com.miniagent.agent.execution.ActionJournal;
import com.miniagent.agent.execution.ActionJournalEntry;
import com.miniagent.agent.execution.ActionJournalKey;
import com.miniagent.common.io.AgentDataPaths;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.NoSuchFileException;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The database is the sole execution authority, including on the desktop.
 * Every permit and outcome commits independently of any enclosing application
 * transaction. No Redis/local-file fallback and no takeover of RUNNING receipts:
 * another replica, or an uncooperative handler, may still be executing them.
 */
@Component
@DependsOnDatabaseInitialization
public final class DbActionJournal implements ActionJournal {
    private static final Logger log = LoggerFactory.getLogger(DbActionJournal.class);
    private static final String LEGACY_REVIEW_REQUIRED = "ACTION_JOURNAL_LEGACY_REVIEW_REQUIRED";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String COLUMNS = "key_digest,tool_name,arguments_hash,execution_status,attempt,"
            + "timestamp_ms,error_code,diagnostic_message,result_digest,session_id_hash";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate committed;
    private final Path legacyFile;
    private volatile boolean legacyReviewRequired;
    @Value("${agent.action-journal.terminal-retention-days:3650}")
    private int terminalRetentionDays = 3650;

    public DbActionJournal(DataSource dataSource, PlatformTransactionManager transactions, AgentDataPaths paths) {
        jdbc = new JdbcTemplate(dataSource);
        committed = new TransactionTemplate(transactions);
        committed.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        committed.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        legacyFile = paths.root().resolve("executor/action-journal.jsonl");
    }

    /** Import old local receipts once by key, without modifying live shared receipts. */
    @PostConstruct
    public synchronized void initialize() {
        // A damaged source must never silently become an empty history, even if
        // the file is removed or changed after this process first detects it.
        if (legacyReviewRequired) return;
        Map<ActionJournalKey, ActionJournalEntry> latest = new LinkedHashMap<>();
        try {
            // exists() also returns false when access cannot be determined.
            // Only a confirmed missing source permits an empty legacy history.
            try {
                if (!Files.readAttributes(legacyFile, BasicFileAttributes.class).isRegularFile()) {
                    blockLegacyReview();
                    return;
                }
            } catch (NoSuchFileException absent) { return; }
            try (var lines = Files.lines(legacyFile, StandardCharsets.UTF_8)) {
                var iterator = lines.iterator();
                while (iterator.hasNext()) {
                    String line = iterator.next();
                    if (line.isBlank()) continue;
                    ActionJournalEntry entry = JSON.readerFor(ActionJournalEntry.class)
                            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(line);
                    latest.put(entry.key(), entry);
                }
            }
        } catch (Exception failure) {
            // Keep the UI available for diagnosis. Do not expose the parse/read
            // exception: its message/cause can contain raw legacy contents.
            blockLegacyReview();
            return;
        }
        try {
            // Validate the complete source first, then import as one batch. An
            // import failure must not leave only a prefix of old receipts.
            committed.executeWithoutResult(tx -> {
                for (ActionJournalEntry old : latest.values()) {
                    ActionJournalEntry receipt = old.status() == ActionExecutionStatus.RUNNING
                            ? new ActionJournalEntry(old.key(), old.toolName(), old.argumentsHash(),
                            ActionExecutionStatus.UNKNOWN, old.attempt(), old.timestampEpochMillis(),
                            "OUTCOME_UNKNOWN", "旧进程退出前未确认工具结果；需人工核验", "") : old;
                    try { insert(receipt); }
                    catch (DuplicateKeyException alreadyImportedOrRunning) {
                        // Existing database ownership always wins, even if another
                        // replica is executing a copy of the legacy action.
                    }
                }
            });
        } catch (RuntimeException failure) {
            blockLegacyReview();
        }
    }

    private void blockLegacyReview() {
        legacyReviewRequired = true;
        log.error("ACTION_JOURNAL_LEGACY_REVIEW_REQUIRED：旧版动作记录损坏、无法读取或导入失败；"
                + "已阻止工具执行及回执读写，请人工核验历史记录后再恢复。原文件保持不变。");
    }

    private void requireLegacyReady() {
        if (legacyReviewRequired) throw new IllegalStateException(LEGACY_REVIEW_REQUIRED);
    }

    @Override
    public Claim claim(ActionJournalKey key, String toolName, String argumentsHash, boolean allowRetry) {
        return claim(key, toolName, argumentsHash, allowRetry, false);
    }

    @Override
    public Claim claimEffect(ActionJournalKey key, String toolName, String argumentsHash) {
        return claim(key, toolName, argumentsHash, true, true);
    }

    private Claim claim(ActionJournalKey key, String toolName, String argumentsHash,
                        boolean allowRetry, boolean confirmedEffect) {
        requireLegacyReady();
        try {
            return committed.execute(tx -> {
                Optional<ActionJournalEntry> existing = find(key);
                if (existing.isEmpty()) {
                    ActionJournalEntry running = running(key, toolName, argumentsHash, 1);
                    insert(running); // Unique key arbitrates concurrent first dispatches.
                    return new Claim(true, running);
                }
                ActionJournalEntry previous = existing.get();
                boolean retryable = confirmedEffect
                        ? previous.status().terminal() && previous.status() != ActionExecutionStatus.UNKNOWN
                        : allowRetry && (previous.status() == ActionExecutionStatus.FAILED
                        || previous.status() == ActionExecutionStatus.TIMEOUT);
                if (!sameAction(previous, toolName, argumentsHash) || !retryable) return new Claim(false, previous);
                ActionJournalEntry running = running(key, toolName, argumentsHash, Math.addExact(previous.attempt(), 1));
                if (replace(previous, running) == 1) return new Claim(true, running);
                return new Claim(false, find(key).orElseThrow());
            });
        } catch (DuplicateKeyException lostInitialClaim) {
            return new Claim(false, latest(key).orElseThrow());
        }
    }

    @Override
    public void append(ActionJournalEntry entry) {
        requireLegacyReady();
        committed.executeWithoutResult(tx -> {
            ActionJournalEntry previous = find(entry.key())
                    .orElseThrow(() -> new IllegalStateException("ACTION_JOURNAL_CLAIM_REQUIRED"));
            if (!sameAction(previous, entry.toolName(), entry.argumentsHash())
                    || previous.attempt() != entry.attempt()
                    || previous.status() != ActionExecutionStatus.RUNNING || !entry.status().terminal())
                throw new IllegalStateException("ACTION_JOURNAL_STALE_OUTCOME");
            if (replace(previous, entry) != 1) throw new IllegalStateException("ACTION_JOURNAL_STALE_OUTCOME");
        });
    }

    @Override
    public Optional<ActionJournalEntry> latest(ActionJournalKey key) {
        requireLegacyReady();
        return committed.execute(tx -> find(key));
    }

    @Override
    public List<ActionJournalEntry> unresolved() {
        // Keys are deliberately opaque at rest. Human reconciliation uses the
        // planner's execution/node identity, never a blind journal replay.
        return committed.execute(tx -> jdbc.query("SELECT " + COLUMNS + " FROM agent_action_receipt"
                        + " WHERE execution_status IN ('UNKNOWN','RUNNING') ORDER BY timestamp_ms",
                (row, index) -> read(reviewKey(row), row)));
    }

    @Override
    public List<ActionJournalEntry> unresolved(int limit) {
        return unresolved(limit, 0);
    }

    @Override
    public List<ActionJournalEntry> unresolved(int limit, int offset) {
        requireLegacyReady();
        int bounded = Math.max(1, Math.min(500, limit));
        int boundedOffset = Math.max(0, Math.min(1_000_000, offset));
        return committed.execute(tx -> jdbc.query("SELECT " + COLUMNS + " FROM agent_action_receipt"
                        + " WHERE execution_status IN ('UNKNOWN','RUNNING') ORDER BY timestamp_ms,key_digest LIMIT ? OFFSET ?",
                (row, index) -> read(reviewKey(row), row), bounded, boundedOffset));
    }

    /**
     * Resolve an UNKNOWN/RUNNING receipt only after an authenticated operator has
     * decided what happened externally.  The journal remains the execution
     * authority: no tool is invoked here and SUCCESS can never be manufactured by
     * this endpoint.  FAILED deliberately remains retryable only when the normal
     * idempotency policy permits it; CANCELLED permanently suppresses replay.
     */
    @Override
    public Optional<ActionJournalEntry> reconcile(String receiptId,
                                                  ActionExecutionStatus resolution,
                                                  String note) {
        requireLegacyReady();
        if (receiptId == null || !receiptId.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("invalid action receipt id");
        }
        if (resolution != ActionExecutionStatus.FAILED
                && resolution != ActionExecutionStatus.CANCELLED) {
            throw new IllegalArgumentException("reconciliation must resolve to FAILED or CANCELLED");
        }
        String boundedNote = safeDiagnostic(note == null ? "" : note);
        return committed.execute(tx -> {
            int updated = jdbc.update("UPDATE agent_action_receipt SET execution_status=?"
                            + ",timestamp_ms=?,error_code=?,diagnostic_message=?"
                            + " WHERE key_digest=? AND execution_status IN ('UNKNOWN','RUNNING')",
                    resolution.name(), System.currentTimeMillis(), "HUMAN_RECONCILED",
                    boundedNote.isBlank() ? "operator reconciliation" : boundedNote, receiptId);
            if (updated == 0) {
                return Optional.empty();
            }
            return jdbc.query("SELECT " + COLUMNS + " FROM agent_action_receipt WHERE key_digest=?",
                    (row, index) -> read(reviewKey(row), row), receiptId).stream().findFirst();
        });
    }

    /**
     * Keep confirmed receipts long enough to protect old task replays, then prune only
     * confirmed outcomes. RUNNING and UNKNOWN are safety barriers and are never aged out.
     */
    @Scheduled(cron = "${agent.action-journal.purge-cron:0 20 3 * * *}")
    public void purgeExpiredTerminalReceipts() {
        if (legacyReviewRequired) return;
        int retentionDays = Math.max(365, Math.min(36_500, terminalRetentionDays));
        long cutoff = java.time.Instant.now().minus(java.time.Duration.ofDays(retentionDays)).toEpochMilli();
        try {
            int removed = purgeTerminalReceiptsBefore(cutoff);
            if (removed > 0) log.info("已清理 {} 条超过 {} 天保留期的终态动作回执", removed, retentionDays);
        } catch (RuntimeException failure) {
            // A failed maintenance pass must never change execution authority.
            log.warn("动作回执保留期清理失败；未改变执行状态", failure);
        }
    }

    int purgeTerminalReceiptsBefore(long cutoffEpochMillis) {
        requireLegacyReady();
        return committed.execute(status -> jdbc.update("DELETE FROM agent_action_receipt"
                        + " WHERE timestamp_ms < ? AND execution_status IN ('SUCCEEDED','FAILED','TIMEOUT','CANCELLED')",
                cutoffEpochMillis));
    }

    private static ActionJournalKey reviewKey(java.sql.ResultSet row) throws java.sql.SQLException {
        // The review DTO receives receipt/session hashes from ActionJournalEntry;
        // this synthetic key exists only to satisfy the journal entry model.
        return new ActionJournalKey("receipt:" + row.getString("key_digest"), "opaque", "opaque", "opaque");
    }

    private Optional<ActionJournalEntry> find(ActionJournalKey key) {
        return jdbc.query("SELECT " + COLUMNS + " FROM agent_action_receipt WHERE key_digest=?",
                (row, index) -> read(key, row), keyDigest(key)).stream().findFirst();
    }

    private void insert(ActionJournalEntry entry) {
        jdbc.update("INSERT INTO agent_action_receipt (" + COLUMNS + ") VALUES (?,?,?,?,?,?,?,?,?,?)",
                keyDigest(entry.key()), entry.toolName(), entry.argumentsHash(), entry.status().name(),
                entry.attempt(), entry.timestampEpochMillis(), safeCode(entry.errorCode()),
                safeDiagnostic(entry), safeDigest(entry.resultDigest()), sessionIdHash(entry.key().sessionId()));
    }

    private int replace(ActionJournalEntry previous, ActionJournalEntry next) {
        return jdbc.update("UPDATE agent_action_receipt SET execution_status=?,attempt=?,timestamp_ms=?,"
                        + "error_code=?,diagnostic_message=?,result_digest=? WHERE key_digest=?"
                        + " AND execution_status=? AND attempt=? AND tool_name=? AND arguments_hash=?",
                next.status().name(), next.attempt(), next.timestampEpochMillis(), safeCode(next.errorCode()),
                safeDiagnostic(next), safeDigest(next.resultDigest()), keyDigest(previous.key()),
                previous.status().name(), previous.attempt(), previous.toolName(), previous.argumentsHash());
    }

    private static boolean sameAction(ActionJournalEntry entry, String tool, String hash) {
        return entry.toolName().equals(tool) && entry.argumentsHash().equals(hash);
    }

    private static ActionJournalEntry running(ActionJournalKey key, String tool, String hash, int attempt) {
        return new ActionJournalEntry(key, tool, hash, ActionExecutionStatus.RUNNING, attempt,
                System.currentTimeMillis(), "", "", "");
    }

    private static ActionJournalEntry read(ActionJournalKey key, java.sql.ResultSet row) throws java.sql.SQLException {
        return new ActionJournalEntry(key, row.getString("tool_name"), row.getString("arguments_hash"),
                ActionExecutionStatus.valueOf(row.getString("execution_status")), row.getInt("attempt"),
                row.getLong("timestamp_ms"), row.getString("error_code"),
                row.getString("diagnostic_message"), row.getString("result_digest"),
                row.getString("key_digest"), row.getString("session_id_hash"));
    }

    private static String sessionIdHash(String sessionId) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(sessionId.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("ACTION_JOURNAL_HASH_UNAVAILABLE", impossible);
        }
    }

    private static String safeCode(String code) { return code.matches("[A-Z0-9_]{0,64}") ? code : "UNCLASSIFIED"; }
    private static String safeDigest(String digest) { return digest.matches("[a-f0-9]{64}") ? digest : ""; }
    private static String safeDiagnostic(ActionJournalEntry entry) {
        // Tool messages may include provider bodies, user data or credentials.
        // Store only a fixed recovery hint; request/trace storage owns diagnostics.
        return entry.status() == ActionExecutionStatus.UNKNOWN ? "工具结果待核验，禁止自动重试" : "";
    }

    private static String safeDiagnostic(String note) {
        String normalized = note.replaceAll("[\\r\\n\\t]+", " ").trim();
        return normalized.length() <= 1000 ? normalized : normalized.substring(0, 1000);
    }

    private static String keyDigest(ActionJournalKey key) {
        try {
            // Length prefixes prevent separator ambiguity without retaining the
            // original session/node/idempotency keys in the database.
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : List.of(key.sessionId(), key.planVersion(), key.nodeId(), key.idempotencyKey())) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("ACTION_JOURNAL_HASH_UNAVAILABLE", impossible);
        }
    }
}
