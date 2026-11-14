package com.miniagent.agent.browser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BrowserSessionStateStoreTest {
    private static final String FIRST = "browser-" + "1".repeat(64);
    private static final String SECOND = "browser-" + "2".repeat(64);
    private static final String STATE = "{\"cookies\":[{\"name\":\"auth\",\"value\":\"secret-cookie-123\"}],\"origins\":[]}";
    @TempDir Path data;

    @Test
    void savesEncryptedOwnerOnlyStateAndLoadsAfterANewStoreInstance() throws Exception {
        var store = store();
        assertTrue(store.load(FIRST).isEmpty());
        store.save(FIRST, STATE);
        byte[] bytes = Files.readAllBytes(state(FIRST));
        assertFalse(new String(bytes, StandardCharsets.UTF_8).contains("secret-cookie-123"));
        assertEquals(STATE, store().load(FIRST).orElseThrow());
        assertOwnerOnly(data.resolve("browser-sessions"), true);
        assertOwnerOnly(state(FIRST), false);
        assertOwnerOnly(data.resolve("browser-sessions/.key"), false);
    }

    @Test
    void ciphertextCannotBeReusedForAnotherIdentity() throws Exception {
        var store = store();
        store.save(FIRST, STATE);
        Files.copy(state(FIRST), state(SECOND));
        IOException failed = assertThrows(IOException.class, () -> store.load(SECOND));
        assertTrue(failed.getMessage().contains("损坏"));
        assertEquals(STATE, store.load(FIRST).orElseThrow());
    }

    @Test
    void rejectsUntrustedIdentifiersAndNeverCreatesAnOutsideFile() throws Exception {
        for (String identity : List.of("../escape", "default", "chat-a", FIRST + "/../escape")) {
            assertThrows(IOException.class, () -> store().save(identity, STATE));
        }
        assertFalse(Files.exists(data.resolve("escape.state")));
        assertFalse(Files.exists(data.resolve("browser-sessions")));
    }

    @Test
    void corruptedStateFailsWithoutOverwritingItUntilExplicitlyCleared() throws Exception {
        var store = store();
        store.save(FIRST, STATE);
        byte[] corrupted = Files.readAllBytes(state(FIRST));
        corrupted[corrupted.length - 1] ^= 1;
        Files.write(state(FIRST), corrupted);
        assertThrows(IOException.class, () -> store.load(FIRST));
        assertThrows(IOException.class, () -> store.save(FIRST, STATE));
        assertArrayEquals(corrupted, Files.readAllBytes(state(FIRST)));
        store.clear(FIRST);
        assertTrue(store.load(FIRST).isEmpty());
        store.save(FIRST, STATE);
        assertEquals(STATE, store.load(FIRST).orElseThrow());
    }

    @Test
    void missingKeyNeverCreatesAReplacementThatInvalidatesOtherSavedSessions() throws Exception {
        var store = store();
        store.save(FIRST, STATE);
        byte[] saved = Files.readAllBytes(state(FIRST));
        Path key = data.resolve("browser-sessions/.key");
        Files.delete(key);
        assertThrows(IOException.class, () -> store.load(FIRST));
        assertThrows(IOException.class, () -> store.save(SECOND, STATE));
        assertFalse(Files.exists(key));
        assertFalse(Files.exists(state(SECOND)));
        assertArrayEquals(saved, Files.readAllBytes(state(FIRST)));
    }

    @Test
    void oversizeSavePreservesLastGoodStateAndAtomicWritesLeaveNoTemporaryFiles() throws Exception {
        var store = store();
        store.save(FIRST, STATE);
        assertThrows(IOException.class, () -> store.save(FIRST, "x".repeat(4 * 1024 * 1024 + 1)));
        assertEquals(STATE, store.load(FIRST).orElseThrow());
        for (int i = 0; i < 5; i++) store.save(FIRST, STATE + i);
        assertEquals(STATE + "4", store.load(FIRST).orElseThrow());
        try (var paths = Files.list(data.resolve("browser-sessions"))) {
            assertTrue(paths.noneMatch(path -> path.getFileName().toString().startsWith(".pending-")));
        }
    }

    @Test
    void rejectsSymbolicLinkStateWithoutReadingOrChangingTheExternalFile() throws Exception {
        var store = store();
        store.load(FIRST); // Create the protected root before adding the adversarial entry.
        Path outside = data.resolve("outside.json");
        Files.writeString(outside, "keep-private");
        try { Files.createSymbolicLink(state(FIRST), outside); }
        catch (UnsupportedOperationException | IOException unsupported) {
            // Windows may prohibit symlink creation without developer mode; exercise non-file rejection instead.
            Files.createDirectory(state(FIRST));
        }
        assertThrows(IOException.class, () -> store.load(FIRST));
        assertThrows(IOException.class, () -> store.save(FIRST, STATE));
        assertThrows(IOException.class, () -> store.clear(FIRST));
        assertEquals("keep-private", Files.readString(outside));
    }

    private BrowserSessionStateStore store() { return new BrowserSessionStateStore(data.toString()); }
    private Path state(String identity) { return data.resolve("browser-sessions").resolve(identity + ".state"); }

    private static void assertOwnerOnly(Path path, boolean directory) throws Exception {
        var posix = Files.getFileAttributeView(path, PosixFileAttributeView.class);
        if (posix != null) {
            assertEquals(PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"), posix.readAttributes().permissions());
        } else {
            var acl = Files.getFileAttributeView(path, AclFileAttributeView.class);
            assertNotNull(acl);
            assertFalse(acl.getAcl().isEmpty());
            var owner = acl.getOwner();
            assertTrue(acl.getAcl().stream().allMatch(entry -> entry.type() == AclEntryType.ALLOW && entry.principal().equals(owner)));
        }
    }
}
