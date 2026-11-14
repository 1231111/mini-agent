package com.miniagent.agent.todo;

import com.miniagent.agent.tool.WorkspaceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class AcceptanceEvidenceFilesTest {
    @TempDir Path temporary;
    Path workspace;

    @BeforeEach void bindWorkspace() throws Exception {
        workspace = Files.createDirectory(temporary.resolve("workspace"));
        WorkspaceContext.setRootOverride(workspace);
    }
    @AfterEach void clearWorkspace() { WorkspaceContext.clearRootOverride(); }

    @Test void authorizedAbsoluteAndRelativeFilesUseTheSameBoundedSnapshot() throws Exception {
        String content = "<html><body>actual generated artifact</body></html>";
        Path file = Files.writeString(workspace.resolve("page.html"), content);
        assertEquals(content, AcceptanceEvidenceFiles.readText(file.toString()));
        assertEquals(content, AcceptanceEvidenceFiles.readText("page.html"));
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(content.getBytes(StandardCharsets.UTF_8)));
        assertEquals(content, AcceptanceEvidenceFiles.readText("page.html", digest));
        assertEquals(content, AcceptanceEvidenceFiles.readText("page.html", digest.substring(0, 16)));
        Files.writeString(file, "<html>replaced after structure check</html>");
        assertNull(AcceptanceEvidenceFiles.readText("page.html", digest));
    }

    @Test void outsideRootsBinaryInvalidUtf8AndOversizedFilesAreRejected() throws Exception {
        Path outside = Files.writeString(temporary.resolve("secret.txt"), "outside secret");
        assertNull(AcceptanceEvidenceFiles.readText(outside.toString()));
        for (byte[] data : new byte[][]{new byte[128], new byte[]{(byte)0xc3, (byte)0x28},
                "x".repeat(12_001).getBytes(StandardCharsets.UTF_8), new byte[48_001]}) {
            Path file = Files.write(workspace.resolve("invalid.bin"), data);
            assertNull(AcceptanceEvidenceFiles.readText(file.toString()));
        }
        assertNull(AcceptanceEvidenceFiles.readText("missing.html"));
        assertNull(AcceptanceEvidenceFiles.readText(workspace.toString()));
    }

    @Test void symlinkCannotEscapeAnAuthorizedRoot() throws Exception {
        Path outside = Files.createDirectory(temporary.resolve("outside"));
        Files.writeString(outside.resolve("secret.txt"), "outside secret");
        Path link = workspace.resolve("escape");
        boolean linked = false;
        try {
            try { Files.createSymbolicLink(link, outside); linked = true; }
            catch (Exception unsupported) {
                if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
                    Process process = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J",
                            link.toString(), outside.toString()).redirectErrorStream(true).start();
                    linked = process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) && process.exitValue() == 0;
                    if (process.isAlive()) process.destroyForcibly();
                }
            }
            assumeTrue(linked, "symlink/junction creation unavailable");
            assertNull(AcceptanceEvidenceFiles.readText(link.resolve("secret.txt").toString()));
        } finally { if (linked) Files.deleteIfExists(link); }
    }
}
