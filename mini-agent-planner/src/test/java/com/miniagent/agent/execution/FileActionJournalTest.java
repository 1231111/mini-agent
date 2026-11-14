package com.miniagent.agent.execution;

import com.miniagent.common.io.AgentDataPaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class FileActionJournalTest {
    @TempDir Path directory;

    @Test void confirmedEffectsCanRepeatWhileUnknownAndRunningEffectsRemainQuarantined() {
        AgentDataPaths paths = new AgentDataPaths(directory.toString());
        ActionJournalKey key = new ActionJournalKey("session", "execution:1", "effect", "key");
        try (FileActionJournal journal = new FileActionJournal(paths)) {
            journal.initialize();
            ActionJournal.Claim first = journal.claimEffect(key, "click", "hash");
            assertTrue(first.acquired());
            assertFalse(journal.claimEffect(key, "click", "hash").acquired());
            journal.append(terminal(first, ActionExecutionStatus.SUCCEEDED));
            assertFalse(journal.claim(key, "click", "hash", true).acquired());
            ActionJournal.Claim second = journal.claimEffect(key, "click", "hash");
            assertTrue(second.acquired());
            assertEquals(2, second.entry().attempt());
            assertThrows(IllegalStateException.class, () -> journal.append(terminal(first, ActionExecutionStatus.SUCCEEDED)));
            journal.append(terminal(second, ActionExecutionStatus.UNKNOWN));
            assertFalse(journal.claimEffect(key, "click", "hash").acquired());
        }
        try (FileActionJournal restarted = new FileActionJournal(paths)) {
            restarted.initialize();
            assertEquals(ActionExecutionStatus.UNKNOWN, restarted.latest(key).orElseThrow().status());
            assertFalse(restarted.claimEffect(key, "click", "hash").acquired());
        }
    }

    private static ActionJournalEntry terminal(ActionJournal.Claim claim, ActionExecutionStatus status) {
        ActionJournalEntry entry = claim.entry();
        return new ActionJournalEntry(entry.key(), entry.toolName(), entry.argumentsHash(), status,
                entry.attempt(), System.currentTimeMillis(), "", "", "");
    }

    @Test void secondInstanceCannotReclassifyAnActiveHandlersRunningReceipt() {
        AgentDataPaths paths = new AgentDataPaths(directory.toString());
        ActionJournalKey key = new ActionJournalKey("session", "execution:1", "send", "key");
        try (FileActionJournal first = new FileActionJournal(paths)) {
            first.initialize();
            assertTrue(first.claim(key, "send", "hash", false).acquired());
            try (FileActionJournal second = new FileActionJournal(paths)) {
                assertThrows(IllegalStateException.class, second::initialize);
            }
            assertEquals(ActionExecutionStatus.RUNNING, first.latest(key).orElseThrow().status());
            assertFalse(first.claim(key, "send", "hash", true).acquired());
        }
        try (FileActionJournal restarted = new FileActionJournal(paths)) {
            restarted.initialize();
            assertEquals(ActionExecutionStatus.UNKNOWN, restarted.latest(key).orElseThrow().status());
            assertFalse(restarted.claim(key, "send", "hash", true).acquired());
        }
    }
}
