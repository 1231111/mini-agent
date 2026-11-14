package com.miniagent.agent.todo;

import com.miniagent.agent.core.ModelCallControl;
import com.miniagent.agent.tool.BuiltinTools;
import com.miniagent.agent.tool.PathGuard;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import java.util.HexFormat;
import java.security.MessageDigest;

/** One bounded, authorized UTF-8 snapshot; unreadable or changing artifacts are not evidence. */
public final class AcceptanceEvidenceFiles {
    private static final int MAX_BYTES = 48_000;
    private static final int MAX_CHARS = 12_000;

    private AcceptanceEvidenceFiles() { }

    public static String readText(String declaredPath) {
        return readText(declaredPath, "");
    }

    public static String readText(String declaredPath, String expectedSha256) {
        if (declaredPath == null || declaredPath.isBlank()) return null;
        try {
            Path candidate = resolveDeclaredFile(declaredPath);
            if (candidate == null) return null;
            // Resolve junctions/symlinks before applying the same authorized roots as read_file.
            Path real = candidate.toRealPath();
            PathGuard.assertAllowed(real, "semantic acceptance read");
            BasicFileAttributes before = Files.readAttributes(real, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (!before.isRegularFile() || before.size() > MAX_BYTES) return null;
            byte[] bytes;
            // No size-then-unbounded-read race: even a concurrently growing file is capped.
            try (InputStream input = Files.newInputStream(real, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                bytes = input.readNBytes(MAX_BYTES + 1);
            }
            if (bytes.length > MAX_BYTES) return null;
            if (expectedSha256 != null && !expectedSha256.isBlank()) {
                String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
                // Todo's persisted validation hash uses a 16-character SHA256 prefix; newer callers may use all 64.
                if ((expectedSha256.length() != 16 && expectedSha256.length() != 64)
                        || !actual.startsWith(expectedSha256)) return null;
            }
            BasicFileAttributes after = Files.readAttributes(real, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            // Windows' default provider returns a null fileKey. Keep creation time as an additional
            // identity check there; providers exposing fileKey must also retain that identity.
            if (!after.isRegularFile() || !Objects.equals(before.fileKey(), after.fileKey())
                    || !before.creationTime().equals(after.creationTime())
                    || before.size() != after.size() || bytes.length != after.size()
                    || !before.lastModifiedTime().equals(after.lastModifiedTime())
                    || !candidate.toRealPath().equals(real)) return null;
            PathGuard.assertAllowed(real.toRealPath(), "semantic acceptance read");
            String content = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            return content.isBlank() || content.length() > MAX_CHARS || content.indexOf('\0') >= 0 ? null : content;
        } catch (Exception failure) {
            ModelCallControl.rethrowIfStopped(failure);
            return null;
        }
    }

    /** Resolve exactly as the writer does; neither evidence nor a same-named file can replace this path. */
    public static Path resolveDeclaredFile(String declaredPath) {
        if (declaredPath == null || declaredPath.isBlank()) return null;
        try {
            Path declared = Path.of(declaredPath.replace('\\', '/'));
            Path candidate = declared.isAbsolute() ? declared.normalize() : BuiltinTools.resolveOutputPath(declaredPath);
            Path real = candidate.toRealPath();
            PathGuard.assertAllowed(real, "artifact acceptance");
            return Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS) ? candidate : null;
        } catch (Exception invalid) {
            ModelCallControl.rethrowIfStopped(invalid);
            return null;
        }
    }
}
