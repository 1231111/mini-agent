package com.miniagent.agent.browser;

import com.miniagent.common.io.AgentDataPaths;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.*;

/** Encrypted Playwright state, scoped to the authenticated browser identity, never a model-selected path. */
@Component
public final class BrowserSessionStateStore {
    private static final int MAX_STATE_BYTES = 4 * 1024 * 1024;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Object KEY_CREATION = new Object();
    private final Path root;

    @Autowired
    public BrowserSessionStateStore(AgentDataPaths paths) {
        root = paths.root().resolve("browser-sessions");
    }

    public BrowserSessionStateStore(String dataDir) { this(new AgentDataPaths(dataDir)); }

    public Optional<String> load(String identity) throws IOException {
        Path target = statePath(identity);
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        verifyRegularFile(target);
        byte[] encrypted = boundedRead(target, MAX_STATE_BYTES + 64);
        if (encrypted.length < 29 || encrypted.length > MAX_STATE_BYTES + 64 || encrypted[0] != 1)
            throw invalidState();
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key(false), "AES"),
                    new GCMParameterSpec(128, Arrays.copyOfRange(encrypted, 1, 13)));
            cipher.updateAAD(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] plain = cipher.doFinal(encrypted, 13, encrypted.length - 13);
            return Optional.of(new String(plain, java.nio.charset.StandardCharsets.UTF_8));
        } catch (GeneralSecurityException invalid) { throw invalidState(); }
    }

    public void save(String identity, String state) throws IOException {
        Path target = statePath(identity);
        byte[] plain = Objects.requireNonNull(state).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (plain.length > MAX_STATE_BYTES) throw new IOException("浏览器会话状态超过4MiB保存上限");
        // Corruption is a visible recovery problem, never permission to silently overwrite authentication.
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) load(identity);
        byte[] nonce = new byte[12]; RANDOM.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key(true), "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] encrypted = cipher.doFinal(plain);
            ByteBuffer output = ByteBuffer.allocate(13 + encrypted.length).put((byte) 1).put(nonce).put(encrypted);
            atomicWrite(target, output.array());
        } catch (GeneralSecurityException unavailable) { throw new IOException("浏览器会话状态加密失败", unavailable); }
    }

    /** Explicit reset only: normal browser_close and shutdown preserve authentication. */
    public void clear(String identity) throws IOException {
        Path target = statePath(identity);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            verifyRegularFile(target);
            Files.delete(target);
        }
    }

    private Path statePath(String identity) throws IOException {
        if (identity == null || !identity.matches("browser-[0-9a-f]{64}"))
            throw new IOException("浏览器会话持久化需要可信的用户与会话标识");
        ensureRoot();
        return root.resolve(identity + ".state");
    }

    private void ensureRoot() throws IOException {
        Files.createDirectories(root.getParent());
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            try { Files.createDirectory(root); }
            catch (FileAlreadyExistsException raced) { /* Verify the winner below. */ }
        }
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                || !root.toRealPath().equals(root.getParent().toRealPath().resolve(root.getFileName())))
            throw new IOException("浏览器会话目录必须是受保护的普通目录");
        restrict(root);
    }

    private byte[] key(boolean allowCreate) throws IOException {
        Path target = root.resolve(".key");
        synchronized (KEY_CREATION) {
            if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                if (!allowCreate) throw invalidState();
                // Lock key creation between application instances; never replace another process's key.
                Path lockPath = root.resolve(".key.lock");
                try (FileChannel lock = FileChannel.open(lockPath, StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                    restrict(lockPath);
                    try (var held = lock.tryLock()) {
                        if (held == null) throw new IOException("另一实例正在初始化浏览器会话密钥，请稍后重试");
                        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                            // A missing key cannot be replaced while saved ciphertext still depends on it.
                            try (DirectoryStream<Path> states = Files.newDirectoryStream(root, "*.state")) {
                                if (states.iterator().hasNext()) throw invalidState();
                            }
                            byte[] key = new byte[32]; RANDOM.nextBytes(key);
                            atomicWrite(target, key);
                        }
                    }
                }
            }
            verifyRegularFile(target);
            byte[] key = boundedRead(target, 32);
            if (key.length != 32) throw invalidState();
            return key;
        }
    }

    private static byte[] boundedRead(Path path, int maximum) throws IOException {
        try (var input = Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(maximum + 1);
            if (bytes.length > maximum) throw invalidState();
            return bytes;
        }
    }

    private void atomicWrite(Path target, byte[] bytes) throws IOException {
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) verifyRegularFile(target);
        Path temporary = Files.createTempFile(root, ".pending-", ".tmp");
        try {
            restrict(temporary);
            try (FileChannel out = FileChannel.open(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) out.write(buffer);
                out.force(true);
            }
            try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unsupported) {
                throw new IOException("浏览器会话目录不支持原子写入，未替换原状态", unsupported);
            }
        } finally { Files.deleteIfExists(temporary); }
    }

    private void verifyRegularFile(Path path) throws IOException {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || !path.toRealPath().getParent().equals(root.toRealPath())
                || Files.size(path) > MAX_STATE_BYTES + 64) throw invalidState();
        restrict(path);
    }

    private static void restrict(Path path) throws IOException {
        PosixFileAttributeView posix = Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (posix != null) {
            posix.setPermissions(Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                    ? PosixFilePermissions.fromString("rwx------") : PosixFilePermissions.fromString("rw-------"));
            return;
        }
        AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (acl != null) {
            acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(acl.getOwner())
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build()));
            return;
        }
        throw new IOException("文件系统不支持仅所有者访问的浏览器会话存储");
    }

    private static IOException invalidState() {
        return new IOException("浏览器保存的登录态已损坏或无法验证；请显式清除此会话登录态后重试，不能静默覆盖");
    }
}
