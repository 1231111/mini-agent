package com.miniagent.agent.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.memory.AgentDataPaths;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 追加式 JSONL Journal；启动时将遗留 RUNNING 标为 UNKNOWN。 */
@Slf4j
@Component
public class FileActionJournal implements ActionJournal {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Path journalFile;
    private final Map<ActionJournalKey, ActionJournalEntry> latest = new ConcurrentHashMap<>();

    public FileActionJournal(AgentDataPaths dataPaths) {
        this.journalFile = dataPaths.root().resolve("executor").resolve("action-journal.jsonl");
    }

    @PostConstruct
    void initialize() {
        try {
            Files.createDirectories(journalFile.getParent());
            if (Files.exists(journalFile)) {
                for (String line : Files.readAllLines(journalFile, StandardCharsets.UTF_8)) {
                    if (line.isBlank()) continue;
                    try { latest.put(JSON.readValue(line, ActionJournalEntry.class).key(), JSON.readValue(line, ActionJournalEntry.class)); }
                    catch (Exception ignored) { log.warn("跳过损坏的 Action Journal 记录: {}", journalFile); }
                }
            }
            for (ActionJournalEntry entry : latest.values().stream()
                    .filter(e -> e.status() == ActionExecutionStatus.RUNNING).toList()) {
                append(new ActionJournalEntry(entry.key(), entry.toolName(), entry.argumentsHash(),
                        ActionExecutionStatus.UNKNOWN, entry.attempt(), System.currentTimeMillis(),
                        "OUTCOME_UNKNOWN", "进程在工具终态前退出；需人工核验", ""));
            }
        } catch (Exception e) {
            throw new IllegalStateException("无法初始化 Action Journal: " + journalFile, e);
        }
    }

    @Override
    public synchronized void append(ActionJournalEntry entry) {
        ActionJournalEntry previous = latest.get(entry.key());
        if (previous == null && entry.status() != ActionExecutionStatus.PLANNED) {
            throw new IllegalStateException("Action Journal 首状态必须是 PLANNED: " + entry.key());
        }
        if (previous != null && !previous.status().canTransitionTo(entry.status())) {
            throw new IllegalStateException("非法执行状态迁移: " + previous.status() + " -> " + entry.status());
        }
        try {
            Files.writeString(journalFile, JSON.writeValueAsString(entry) + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
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
}
