package com.miniagent.agent.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.common.io.AgentDataPaths;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Legacy single-process JSONL journal, also used by isolated tool probes.
 * Production uses the shared database journal. Hold an OS lock for the complete
 * process lifetime so a second reader cannot reset an active RUNNING action.
 */
@Slf4j
public class FileActionJournal implements ActionJournal, AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Path journalFile;
    private final Map<ActionJournalKey, ActionJournalEntry> latest = new ConcurrentHashMap<>();
    private FileChannel lockChannel;
    private FileLock processLock;

    public FileActionJournal(AgentDataPaths dataPaths) {
        this.journalFile = dataPaths.root().resolve("executor").resolve("action-journal.jsonl");
    }

    @PostConstruct
    synchronized void initialize() {
        try {
            if (processLock != null && processLock.isValid()) return;
            Files.createDirectories(journalFile.getParent());
            lockChannel = FileChannel.open(journalFile.resolveSibling("action-journal.lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            processLock = lockChannel.tryLock();
            if (processLock == null) throw new IllegalStateException("ACTION_JOURNAL_ALREADY_OPEN");
            if (Files.exists(journalFile)) {
                for (String line : Files.readAllLines(journalFile, StandardCharsets.UTF_8)) {
                    if (line.isBlank()) {
                        continue;
                    }
                    ActionJournalEntry entry = JSON.readValue(line, ActionJournalEntry.class);
                    latest.put(entry.key(), entry);
                }
            }
            for (ActionJournalEntry entry : latest.values().stream()
                    .filter(e -> e.status() == ActionExecutionStatus.RUNNING).toList()) {
                append(new ActionJournalEntry(entry.key(), entry.toolName(), entry.argumentsHash(),
                        ActionExecutionStatus.UNKNOWN, entry.attempt(), System.currentTimeMillis(),
                        "OUTCOME_UNKNOWN", "进程在工具终态前退出；需人工核验", ""));
            }
        } catch (Exception e) {
            close();
            throw new IllegalStateException("无法初始化 Action Journal: " + journalFile, e);
        }
    }

    @Override
    public synchronized Claim claim(ActionJournalKey key, String toolName, String argumentsHash, boolean allowRetry) {
        return claim(key, toolName, argumentsHash, allowRetry, false);
    }

    @Override
    public synchronized Claim claimEffect(ActionJournalKey key, String toolName, String argumentsHash) {
        return claim(key, toolName, argumentsHash, true, true);
    }

    private Claim claim(ActionJournalKey key, String toolName, String argumentsHash,
                        boolean allowRetry, boolean confirmedEffect) {
        requireOpen();
        ActionJournalEntry previous = latest.get(key);
        boolean retryable = previous != null && (confirmedEffect
                ? previous.status().terminal() && previous.status() != ActionExecutionStatus.UNKNOWN
                : allowRetry && (previous.status() == ActionExecutionStatus.FAILED
                || previous.status() == ActionExecutionStatus.TIMEOUT));
        if (previous != null && (!previous.toolName().equals(toolName)
                || !previous.argumentsHash().equals(argumentsHash) || !retryable))
            return new Claim(false, previous);
        int attempt = previous == null ? 1 : Math.addExact(previous.attempt(), 1);
        if (previous == null) append(new ActionJournalEntry(key, toolName, argumentsHash,
                ActionExecutionStatus.PLANNED, 0, System.currentTimeMillis(), "", "", ""));
        append(new ActionJournalEntry(key, toolName, argumentsHash,
                ActionExecutionStatus.READY, attempt, System.currentTimeMillis(), "", "", ""), confirmedEffect);
        ActionJournalEntry running = new ActionJournalEntry(key, toolName, argumentsHash,
                ActionExecutionStatus.RUNNING, attempt, System.currentTimeMillis(), "", "", "");
        append(running);
        return new Claim(true, running);
    }

    @Override
    public synchronized void append(ActionJournalEntry entry) {
        append(entry, false);
    }

    private void append(ActionJournalEntry entry, boolean confirmedEffect) {
        requireOpen();
        ActionJournalEntry previous = latest.get(entry.key());
        if (previous == null && entry.status() != ActionExecutionStatus.PLANNED) {
            throw new IllegalStateException("Action Journal 首状态必须是 PLANNED: " + entry.key());
        }
        boolean repeatConfirmed = confirmedEffect && previous != null && previous.status().terminal()
                && previous.status() != ActionExecutionStatus.UNKNOWN && entry.status() == ActionExecutionStatus.READY;
        if (previous != null && !repeatConfirmed && !previous.status().canTransitionTo(entry.status())) {
            throw new IllegalStateException("非法执行状态迁移: " + previous.status() + " -> " + entry.status());
        }
        if (previous != null && (!previous.toolName().equals(entry.toolName())
                || !previous.argumentsHash().equals(entry.argumentsHash())
                || entry.attempt() != (entry.status() == ActionExecutionStatus.READY
                ? Math.addExact(previous.attempt(), 1) : previous.attempt())))
            throw new IllegalStateException("ACTION_JOURNAL_STALE_OUTCOME");
        try {
            byte[] serialized = (JSON.writeValueAsString(entry) + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
            try (FileChannel output = FileChannel.open(journalFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND)) {
                ByteBuffer buffer = ByteBuffer.wrap(serialized);
                while (buffer.hasRemaining()) output.write(buffer);
                output.force(true);
            }
            latest.put(entry.key(), entry);
        } catch (Exception e) {
            throw new IllegalStateException("写入 Action Journal 失败: " + journalFile, e);
        }
    }

    @Override public Optional<ActionJournalEntry> latest(ActionJournalKey key) { return Optional.ofNullable(latest.get(key)); }
    @Override public List<ActionJournalEntry> unresolved() {
        return latest.values().stream().filter(e -> e.status() == ActionExecutionStatus.UNKNOWN)
                .sorted(Comparator.comparingLong(ActionJournalEntry::timestampEpochMillis)).toList();
    }

    private void requireOpen() {
        if (processLock == null || !processLock.isValid()) throw new IllegalStateException("ACTION_JOURNAL_NOT_OPEN");
    }

    @Override
    @PreDestroy
    public synchronized void close() {
        try { if (processLock != null) processLock.release(); } catch (Exception ignored) { }
        try { if (lockChannel != null) lockChannel.close(); } catch (Exception ignored) { }
        processLock = null;
        lockChannel = null;
    }
}
