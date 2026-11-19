package com.miniagent.agent.tool;

import com.miniagent.common.context.OwnerContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class FileChangeLedgerTest {
    private final FileChangeLedger ledger = new FileChangeLedger();

    @AfterEach
    void clearContext() {
        WorkspaceContext.clearAll();
        OwnerContext.clearCurrentUser();
        OwnerContext.clearCurrentTenant();
    }

    @Test
    void undoRestoresPreviousTextOnlyWhenCurrentHashMatches() throws Exception {
        Path file = testPath("existing.txt");
        Files.writeString(file, "before");
        bind("session-restore");
        FileChangeLedger.Snapshot snapshot = ledger.capture(file);
        assertTrue(snapshot.accepted());
        Files.writeString(file, "after");
        ledger.record(snapshot, "after".getBytes(StandardCharsets.UTF_8));

        assertTrue(ledger.undoLast().contains("\"success\":true"));
        assertEquals("before", Files.readString(file));
    }

    @Test
    void multipleEditsCanBeUndoneInReverseOrder() throws Exception {
        Path file = testPath("stacked.txt");
        Files.writeString(file, "one");
        bind("session-stacked");
        FileChangeLedger.Snapshot first = ledger.capture(file);
        Files.writeString(file, "two");
        ledger.record(first, "two".getBytes(StandardCharsets.UTF_8));
        FileChangeLedger.Snapshot second = ledger.capture(file);
        Files.writeString(file, "three");
        ledger.record(second, "three".getBytes(StandardCharsets.UTF_8));

        assertTrue(ledger.undoLast().contains("\"success\":true"));
        assertEquals("two", Files.readString(file));
        assertTrue(ledger.undoLast().contains("\"success\":true"));
        assertEquals("one", Files.readString(file));
    }

    @Test
    void undoDeletesNewFileButNeverOverwritesLaterExternalChange() throws Exception {
        Path file = testPath("created.txt");
        Files.deleteIfExists(file);
        bind("session-created");
        FileChangeLedger.Snapshot snapshot = ledger.capture(file);
        Files.writeString(file, "agent result");
        ledger.record(snapshot, "agent result".getBytes(StandardCharsets.UTF_8));
        Files.writeString(file, "human follow-up");

        String result = ledger.undoLast();
        assertTrue(result.contains("撤销已拒绝"));
        assertEquals("human follow-up", Files.readString(file));
    }

    @Test
    void undoHistoryIsScopedBySessionAndOwner() throws Exception {
        Path file = testPath("scoped.txt");
        Files.writeString(file, "before");
        bind("session-one");
        FileChangeLedger.Snapshot snapshot = ledger.capture(file);
        Files.writeString(file, "after");
        ledger.record(snapshot, "after".getBytes(StandardCharsets.UTF_8));

        bind("session-two");
        assertTrue(ledger.undoLast().contains("没有可撤销"));
        bind("session-one");
        assertTrue(ledger.undoLast().contains("\"success\":true"));
    }

    @Test
    void snapshotDetectsChangesBetweenReadAndWriteAndCanBeReleased() throws Exception {
        Path file = testPath("read-race.txt");
        Files.writeString(file, "read value");
        bind("session-read-race");
        FileChangeLedger.Snapshot snapshot = ledger.capture(file);
        Files.writeString(file, "external value");

        assertNotNull(ledger.verifyUnchanged(snapshot, "read value".getBytes(StandardCharsets.UTF_8)));
        ledger.cancel(snapshot);
        FileChangeLedger.Snapshot next = ledger.capture(file);
        assertTrue(next.accepted());
        ledger.cancel(next);
    }

    private static Path testPath(String name) throws Exception {
        Path root = Path.of(System.getProperty("user.dir"), "target", "file-ledger-tests");
        Files.createDirectories(root);
        return root.resolve(name);
    }

    private static void bind(String sessionId) {
        OwnerContext.setCurrentUser(901L);
        OwnerContext.setCurrentTenant("ledger-test");
        WorkspaceContext.setSessionOverride(sessionId);
        WorkspaceContext.setOperationRootSession(sessionId);
    }
}
