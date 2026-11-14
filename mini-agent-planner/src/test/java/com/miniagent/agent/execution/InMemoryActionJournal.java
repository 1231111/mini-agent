package com.miniagent.agent.execution;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 单测用 journal，不碰磁盘。 */
public final class InMemoryActionJournal implements ActionJournal {

    private final Map<ActionJournalKey, ActionJournalEntry> latest = new ConcurrentHashMap<>();

    @Override
    public Claim claim(ActionJournalKey key, String toolName, String argumentsHash, boolean allowRetry) {
        return claimEntry(latest, key, toolName, argumentsHash, allowRetry);
    }

    @Override public Claim claimEffect(ActionJournalKey key, String toolName, String argumentsHash) {
        return claimEffectEntry(latest, key, toolName, argumentsHash);
    }

    public static Claim claimEntry(Map<ActionJournalKey, ActionJournalEntry> entries, ActionJournalKey key,
                                   String toolName, String argumentsHash, boolean allowRetry) {
        return claimEntry(entries, key, toolName, argumentsHash, allowRetry, false);
    }

    public static Claim claimEffectEntry(Map<ActionJournalKey, ActionJournalEntry> entries, ActionJournalKey key,
                                         String toolName, String argumentsHash) {
        return claimEntry(entries, key, toolName, argumentsHash, true, true);
    }

    private static Claim claimEntry(Map<ActionJournalKey, ActionJournalEntry> entries, ActionJournalKey key,
                                    String toolName, String argumentsHash, boolean allowRetry, boolean confirmedEffect) {
        synchronized (entries) {
            ActionJournalEntry previous = entries.get(key);
            boolean retryable = previous != null && (confirmedEffect
                    ? previous.status().terminal() && previous.status() != ActionExecutionStatus.UNKNOWN
                    : allowRetry && (previous.status() == ActionExecutionStatus.FAILED
                    || previous.status() == ActionExecutionStatus.TIMEOUT));
            if (previous != null && (!previous.toolName().equals(toolName)
                    || !previous.argumentsHash().equals(argumentsHash) || !retryable))
                return new Claim(false, previous);
            ActionJournalEntry running = new ActionJournalEntry(key, toolName, argumentsHash,
                    ActionExecutionStatus.RUNNING, previous == null ? 1 : Math.addExact(previous.attempt(), 1),
                    System.currentTimeMillis(), "", "", "");
            entries.put(key, running);
            return new Claim(true, running);
        }
    }

    @Override
    public void append(ActionJournalEntry entry) {
        if (entry != null && entry.key() != null) {
            latest.put(entry.key(), entry);
        }
    }

    @Override
    public Optional<ActionJournalEntry> latest(ActionJournalKey key) {
        return Optional.ofNullable(latest.get(key));
    }

    @Override
    public List<ActionJournalEntry> unresolved() {
        return List.of();
    }
}
