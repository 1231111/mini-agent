package com.miniagent.agent.execution;

import java.util.List;
import java.util.Optional;

public interface ActionJournal {
    void append(ActionJournalEntry entry);
    Optional<ActionJournalEntry> latest(ActionJournalKey key);
    List<ActionJournalEntry> unresolved();
}
