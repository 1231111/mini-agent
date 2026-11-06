package com.miniagent.agent.execution;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 单测用 journal，不碰磁盘。 */
public final class InMemoryActionJournal implements ActionJournal {

    private final Map<ActionJournalKey, ActionJournalEntry> latest = new ConcurrentHashMap<>();

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
