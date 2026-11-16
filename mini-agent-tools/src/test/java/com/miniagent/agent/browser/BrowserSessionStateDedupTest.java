package com.miniagent.agent.browser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class BrowserSessionStateDedupTest {
    private static final String IDENTITY = "browser-" + "a".repeat(64);
    private static final String OTHER = "browser-" + "b".repeat(64);
    private static final String STATE = "{\"cookies\":[{\"name\":\"fixture-auth\",\"value\":\"fixture-only\"}],\"origins\":[]}";
    @TempDir Path data;

    @Test void unchangedStateSkipsCiphertextReplacementAndPreservesTimestamp() throws Exception {
        BrowserSessionStateStore store = store();
        store.save(IDENTITY, STATE);
        byte[] ciphertext = Files.readAllBytes(state());
        FileTime historical = FileTime.fromMillis(1_600_000_000_000L);
        Files.setLastModifiedTime(state(), historical);
        for (int i = 0; i < 5; i++) store.save(IDENTITY, STATE);

        assertArrayEquals(ciphertext, Files.readAllBytes(state()));
        assertEquals(historical, Files.getLastModifiedTime(state()));
        assertEquals(STATE, store.load(IDENTITY).orElseThrow());
        assertOwnerOnly(data.resolve("browser-sessions"), true);
        assertOwnerOnly(state(), false);
        assertOwnerOnly(key(), false);
        try (var paths = Files.list(data.resolve("browser-sessions"))) {
            assertTrue(paths.noneMatch(path -> path.getFileName().toString().startsWith(".pending-")));
        }
    }

    @Test void unchangedStateAfterRestartAlsoSkipsReplacementWithoutAnInMemoryCache() throws Exception {
        store().save(IDENTITY, STATE);
        byte[] ciphertext = Files.readAllBytes(state());
        store().save(IDENTITY, STATE);
        assertArrayEquals(ciphertext, Files.readAllBytes(state()));
    }

    @Test void aRealStateChangeIsStillWrittenAndCanBeRestored() throws Exception {
        BrowserSessionStateStore store = store();
        store.save(IDENTITY, STATE);
        byte[] ciphertext = Files.readAllBytes(state());
        String changed = STATE.replace("fixture-only", "changed-fixture");
        store.save(IDENTITY, changed);
        assertFalse(Arrays.equals(ciphertext, Files.readAllBytes(state())));
        assertEquals(changed, store().load(IDENTITY).orElseThrow());
    }

    @Test void aValidExternalUpdateIsComparedAgainstTheActualFileInsteadOfAStaleCachedValue() throws Exception {
        BrowserSessionStateStore original = store();
        original.save(IDENTITY, STATE);
        String externalUpdate = STATE.replace("fixture-only", "external-fixture-update");
        store().save(IDENTITY, externalUpdate);
        byte[] externallySaved = Files.readAllBytes(state());

        original.save(IDENTITY, STATE);

        assertEquals(STATE, store().load(IDENTITY).orElseThrow());
        assertFalse(Arrays.equals(externallySaved, Files.readAllBytes(state())));
    }

    @Test void identicalCallerStateCannotMaskExternalCiphertextCorruption() throws Exception {
        BrowserSessionStateStore store = store();
        store.save(IDENTITY, STATE);
        byte[] corrupted = Files.readAllBytes(state());
        corrupted[corrupted.length - 1] ^= 1;
        Files.write(state(), corrupted);

        assertThrows(IOException.class, () -> store.save(IDENTITY, STATE));
        assertArrayEquals(corrupted, Files.readAllBytes(state()));
    }

    @Test void identicalCallerStateCannotMaskAReplacedCiphertextFromAnotherIdentity() throws Exception {
        BrowserSessionStateStore store = store();
        store.save(IDENTITY, STATE);
        store.save(OTHER, STATE);
        byte[] replacement = Files.readAllBytes(data.resolve("browser-sessions").resolve(OTHER + ".state"));
        Files.write(state(), replacement);

        assertThrows(IOException.class, () -> store.save(IDENTITY, STATE));
        assertArrayEquals(replacement, Files.readAllBytes(state()));
    }

    @Test void deletingTheKeyCannotBeHiddenByAnIdenticalStateOrRecreateTheKey() throws Exception {
        BrowserSessionStateStore store = store();
        store.save(IDENTITY, STATE);
        byte[] ciphertext = Files.readAllBytes(state());
        Files.delete(key());

        assertThrows(IOException.class, () -> store.save(IDENTITY, STATE));
        assertFalse(Files.exists(key()));
        assertArrayEquals(ciphertext, Files.readAllBytes(state()));
    }

    @Test void replacingTheKeyCannotBeHiddenByAnIdenticalState() throws Exception {
        BrowserSessionStateStore store = store();
        store.save(IDENTITY, STATE);
        byte[] ciphertext = Files.readAllBytes(state());
        byte[] changedKey = Files.readAllBytes(key());
        changedKey[0] ^= 1;
        Files.write(key(), changedKey);

        assertThrows(IOException.class, () -> store.save(IDENTITY, STATE));
        assertArrayEquals(changedKey, Files.readAllBytes(key()));
        assertArrayEquals(ciphertext, Files.readAllBytes(state()));
    }

    @Test void replacingTheStateWithANonRegularEntryCannotBeHiddenByAnIdenticalState() throws Exception {
        BrowserSessionStateStore store = store();
        store.save(IDENTITY, STATE);
        Files.delete(state());
        Path outside = data.resolve("outside.json");
        Files.writeString(outside, "keep-external-data");
        try { Files.createSymbolicLink(state(), outside); }
        catch (IOException | UnsupportedOperationException unavailable) { Files.createDirectory(state()); }

        assertThrows(IOException.class, () -> store.save(IDENTITY, STATE));
        assertEquals("keep-external-data", Files.readString(outside));
    }

    @Test void deletingOrExplicitlyClearingStateStillRecreatesItOnTheNextSave() throws Exception {
        BrowserSessionStateStore store = store();
        store.save(IDENTITY, STATE);
        byte[] first = Files.readAllBytes(state());
        Files.delete(state());
        store.save(IDENTITY, STATE);
        assertFalse(Arrays.equals(first, Files.readAllBytes(state())));
        byte[] second = Files.readAllBytes(state());
        store.clear(IDENTITY);
        store.save(IDENTITY, STATE);
        assertFalse(Arrays.equals(second, Files.readAllBytes(state())));
        assertEquals(STATE, store.load(IDENTITY).orElseThrow());
    }

    private BrowserSessionStateStore store() { return new BrowserSessionStateStore(data.toString()); }
    private Path state() { return data.resolve("browser-sessions").resolve(IDENTITY + ".state"); }
    private Path key() { return data.resolve("browser-sessions/.key"); }
    private static void assertOwnerOnly(Path path, boolean directory) throws Exception {
        PosixFileAttributeView posix = Files.getFileAttributeView(path, PosixFileAttributeView.class);
        if (posix != null) assertEquals(PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"), posix.readAttributes().permissions());
        else {
            AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class);
            assertNotNull(acl);
            assertFalse(acl.getAcl().isEmpty());
            var owner = acl.getOwner();
            assertTrue(acl.getAcl().stream().allMatch(entry -> entry.type() == AclEntryType.ALLOW && entry.principal().equals(owner)));
        }
    }
}
