package com.miniagent.agent.execution;

import java.util.List;
import java.util.Optional;

public interface ActionJournal {
    /** Returns an execution permit only after its RUNNING receipt has durably committed. */
    record Claim(boolean acquired, ActionJournalEntry entry) {
        public Claim {
            java.util.Objects.requireNonNull(entry, "entry");
        }
    }

    /**
     * Atomically owns a new action or one explicitly retryable FAILED/TIMEOUT attempt.
     * Implementations without atomic ownership must fail closed, never emulate this
     * with latest()/append() outside a shared transaction or process lock.
     */
    default Claim claim(ActionJournalKey key, String toolName, String argumentsHash, boolean allowRetry) {
        throw new UnsupportedOperationException("ACTION_JOURNAL_ATOMIC_CLAIM_REQUIRED");
    }

    /**
     * Atomically quarantines the same side effect across different model call IDs.
     * Confirmed terminal effects can be performed again by a new operation;
     * RUNNING and UNKNOWN effects can never be taken over automatically.
     */
    default Claim claimEffect(ActionJournalKey key, String toolName, String argumentsHash) {
        throw new UnsupportedOperationException("ACTION_JOURNAL_ATOMIC_EFFECT_CLAIM_REQUIRED");
    }

    void append(ActionJournalEntry entry);
    Optional<ActionJournalEntry> latest(ActionJournalKey key);
    List<ActionJournalEntry> unresolved();
}
