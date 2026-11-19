package com.miniagent.agent.tool;

import com.miniagent.common.context.OwnerContext;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/** Bounded, process-local undo receipts for agent write_file/edit_file operations. */
@Component
public final class FileChangeLedger {
    private static final long MAX_FILE_BYTES = 8L * 1024 * 1024;
    private static final long MAX_SESSION_BYTES = 64L * 1024 * 1024;
    private static final int MAX_SESSION_CHANGES = 100;
    private static final long MAX_GLOBAL_BYTES = 256L * 1024 * 1024;
    private static final int MAX_GLOBAL_CHANGES = 10_000;
    private static final long CHANGE_TTL_MILLIS = Duration.ofHours(24).toMillis();
    private static final long RESERVATION_TTL_MILLIS = Duration.ofMinutes(10).toMillis();

    private final Map<String, Deque<Change>> changes = new HashMap<>();
    private final Map<String, Long> retainedBytes = new HashMap<>();
    private final Map<String, Integer> retainedCounts = new HashMap<>();
    private final Map<String, Reservation> reservations = new HashMap<>();
    private final Map<String, Long> reservedBytes = new HashMap<>();
    private final Map<String, Integer> reservedCounts = new HashMap<>();
    private long globalRetainedBytes;
    private int globalRetainedCount;
    private long globalReservedBytes;
    private int globalReservedCount;

    public record Snapshot(String reservationId, String ledgerKey, Path path, byte[] before,
                           boolean existed, String beforeHash, String error) {
        public Snapshot { before = before == null ? new byte[0] : before.clone(); }
        @Override public byte[] before() { return before.clone(); }
        public boolean accepted() { return error == null; }
    }

    private record Change(Path path, byte[] before, boolean existed, String expectedAfterHash,
                          long retained, long createdAt) {}
    private record Reservation(String ledgerKey, long retained, long createdAt) {}

    public Snapshot capture(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        try { PathGuard.assertAllowed(absolute, "undo snapshot"); }
        catch (RuntimeException denied) {
            return new Snapshot(null, null, absolute, null, false, "", denied.getMessage());
        }
        String key = ledgerKey();
        if (key == null) return new Snapshot(null, null, absolute, null, false, "",
                "无法确定会话身份，拒绝执行无法安全回滚的文件修改");
        try {
            if (Files.isSymbolicLink(absolute))
                return new Snapshot(null, key, absolute, null, true, "", "不支持对符号链接建立回滚记录");
            boolean existed = Files.exists(absolute, LinkOption.NOFOLLOW_LINKS);
            if (existed && !Files.isRegularFile(absolute, LinkOption.NOFOLLOW_LINKS))
                return new Snapshot(null, key, absolute, null, true, "", "仅支持撤销普通文本文件");
            if (existed && Files.size(absolute) > MAX_FILE_BYTES)
                return new Snapshot(null, key, absolute, null, true, "", "文件超过安全回滚快照上限 8 MiB");
            byte[] before = existed ? Files.readAllBytes(absolute) : new byte[0];
            if (before.length > MAX_FILE_BYTES)
                return new Snapshot(null, key, absolute, null, existed, "", "文件超过安全回滚快照上限 8 MiB");
            long now = System.currentTimeMillis();
            String reservationId = UUID.randomUUID().toString();
            synchronized (this) {
                pruneExpired(now);
                long sessionBytes = retainedBytes.getOrDefault(key, 0L) + reservedBytes.getOrDefault(key, 0L);
                int sessionCount = retainedCounts.getOrDefault(key, 0) + reservedCounts.getOrDefault(key, 0);
                if (sessionCount >= MAX_SESSION_CHANGES || sessionBytes + before.length > MAX_SESSION_BYTES
                        || globalRetainedCount + globalReservedCount >= MAX_GLOBAL_CHANGES
                        || globalRetainedBytes + globalReservedBytes + before.length > MAX_GLOBAL_BYTES) {
                    return new Snapshot(null, key, absolute, null, existed, "",
                            "安全回滚记录已达到会话或服务容量上限");
                }
                reservations.put(reservationId, new Reservation(key, before.length, now));
                reservedBytes.merge(key, (long) before.length, Long::sum);
                reservedCounts.merge(key, 1, Integer::sum);
                globalReservedBytes += before.length;
                globalReservedCount++;
            }
            return new Snapshot(reservationId, key, absolute, before, existed, sha256(before), null);
        } catch (IOException failure) {
            return new Snapshot(null, key, absolute, null, false, "",
                    "无法创建安全回滚快照：" + failure.getMessage());
        }
    }

    /** Refuses to apply a write when its source changed after it was read or snapshotted. */
    public String verifyUnchanged(Snapshot snapshot, byte[] expectedBefore) {
        if (snapshot == null) return null;
        if (!snapshot.accepted()) return snapshot.error();
        Path target = snapshot.path();
        try {
            PathGuard.assertAllowed(target, "undo verify");
            if (Files.isSymbolicLink(target)) return "文件在回滚快照后变成符号链接，已拒绝覆盖";
            boolean exists = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
            if (exists != snapshot.existed()) return "文件在回滚快照后发生变化，请重新读取后重试";
            if (exists && Files.size(target) > MAX_FILE_BYTES)
                return "文件在回滚快照后超过安全上限，已拒绝写入";
            byte[] current = exists ? Files.readAllBytes(target) : new byte[0];
            if (!sha256(current).equals(snapshot.beforeHash()))
                return "文件在回滚快照后发生变化，请重新读取后重试";
            if (expectedBefore != null && !MessageDigest.isEqual(snapshot.before(), expectedBefore))
                return "文件在读取与编辑准备期间发生变化，请重新读取后重试";
            return null;
        } catch (Exception failure) {
            return "无法确认文件仍与回滚快照一致，已拒绝写入";
        }
    }

    /** Commit a reserved snapshot and bind undo to the bytes this operation intended to write. */
    public void record(Snapshot snapshot, byte[] expectedAfter) throws IOException {
        if (snapshot == null || snapshot.reservationId() == null || snapshot.ledgerKey() == null) return;
        try {
            Path target = snapshot.path();
            PathGuard.assertAllowed(target, "undo record");
            if (Files.isSymbolicLink(target) || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("写入结果不是普通文件，无法记录撤销");
            if (Files.size(target) > MAX_FILE_BYTES
                    || (expectedAfter != null && expectedAfter.length > MAX_FILE_BYTES))
                throw new IOException("写入结果超过安全回滚上限");
            byte[] after = Files.readAllBytes(target);
            if (after.length > MAX_FILE_BYTES) throw new IOException("写入结果超过安全回滚上限");
            String expectedHash = sha256(expectedAfter == null ? new byte[0] : expectedAfter);
            boolean resultMatches = MessageDigest.isEqual(after,
                    expectedAfter == null ? new byte[0] : expectedAfter);
            synchronized (this) {
                long now = System.currentTimeMillis();
                pruneExpired(now);
                Reservation reservation = reservations.remove(snapshot.reservationId());
                if (reservation == null) throw new IOException("回滚快照已过期或已释放");
                releaseReservation(snapshot.reservationId(), reservation);
                Deque<Change> history = changes.computeIfAbsent(snapshot.ledgerKey(), ignored -> new ArrayDeque<>());
                long retained = snapshot.before().length;
                history.addLast(new Change(target, snapshot.before(), snapshot.existed(), expectedHash,
                        retained, now));
                retainedBytes.merge(snapshot.ledgerKey(), retained, Long::sum);
                retainedCounts.merge(snapshot.ledgerKey(), 1, Integer::sum);
                globalRetainedBytes += retained;
                globalRetainedCount++;
            }
            if (!resultMatches) throw new IOException("文件在写入期间被其他操作改动；撤销记录已保留并会拒绝覆盖");
        } catch (IOException failure) {
            cancel(snapshot);
            throw failure;
        } catch (RuntimeException failure) {
            cancel(snapshot);
            throw new IOException("无法记录安全撤销：" + failure.getMessage(), failure);
        }
    }

    public synchronized void cancel(Snapshot snapshot) {
        if (snapshot == null || snapshot.reservationId() == null) return;
        Reservation reservation = reservations.remove(snapshot.reservationId());
        if (reservation != null) releaseReservation(snapshot.reservationId(), reservation);
    }

    public synchronized String undoLast() {
        pruneExpired(System.currentTimeMillis());
        String key = ledgerKey();
        if (key == null) return "{\"error\":\"当前会话身份不可用，无法安全定位回滚记录\"}";
        Deque<Change> history = changes.get(key);
        if (history == null || history.isEmpty()) return "{\"error\":\"当前会话没有可撤销的文件修改记录\"}";
        Change change = history.peekLast();
        Path target = change.path().toAbsolutePath().normalize();
        try {
            PathGuard.assertAllowed(target, "undo restore");
            if (Files.isSymbolicLink(target) || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(target) > MAX_FILE_BYTES
                    || !sha256(Files.readAllBytes(target)).equals(change.expectedAfterHash()))
                return "{\"error\":\"撤销已拒绝：文件在智能体修改后又发生了变化。请先核对当前文件，再决定如何处理。\"}";
            if (change.existed()) {
                Path temp = Files.createTempFile(target.getParent(), ".miniagent-undo-", ".tmp");
                try {
                    Files.write(temp, change.before(), StandardOpenOption.TRUNCATE_EXISTING);
                    if (Files.isSymbolicLink(target) || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                            || Files.size(target) > MAX_FILE_BYTES
                            || !sha256(Files.readAllBytes(target)).equals(change.expectedAfterHash()))
                        return "{\"error\":\"撤销已拒绝：文件在校验期间发生变化。请核对后重试。\"}";
                    try { Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                    catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                } finally { Files.deleteIfExists(temp); }
            } else {
                if (Files.isSymbolicLink(target) || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                        || Files.size(target) > MAX_FILE_BYTES
                        || !sha256(Files.readAllBytes(target)).equals(change.expectedAfterHash()))
                    return "{\"error\":\"撤销已拒绝：文件在校验期间发生变化。请核对后重试。\"}";
                Files.delete(target);
            }
            history.removeLast();
            releaseChange(key, change);
            if (history.isEmpty()) changes.remove(key);
            return "{\"success\":true,\"message\":\"已安全撤销最近一次智能体文件修改\",\"path\":\""
                    + target.toString().replace("\\", "/") + "\"}";
        } catch (Exception failure) {
            return "{\"error\":\"撤销失败：" + jsonEscape(failure.getMessage()) + "\"}";
        }
    }

    @Scheduled(fixedDelayString = "${agent.tools.file-undo.prune-interval-ms:300000}")
    public synchronized void pruneExpiredEntries() {
        pruneExpired(System.currentTimeMillis());
    }

    private void pruneExpired(long now) {
        Iterator<Map.Entry<String, Deque<Change>>> sessions = changes.entrySet().iterator();
        while (sessions.hasNext()) {
            Map.Entry<String, Deque<Change>> entry = sessions.next();
            Deque<Change> history = entry.getValue();
            while (!history.isEmpty() && now - history.peekFirst().createdAt() > CHANGE_TTL_MILLIS) {
                Change expired = history.removeFirst();
                releaseChange(entry.getKey(), expired);
            }
            if (history.isEmpty()) sessions.remove();
        }
        Iterator<Map.Entry<String, Reservation>> pending = reservations.entrySet().iterator();
        while (pending.hasNext()) {
            Map.Entry<String, Reservation> entry = pending.next();
            if (now - entry.getValue().createdAt() > RESERVATION_TTL_MILLIS) {
                releaseReservation(entry.getKey(), entry.getValue());
                pending.remove();
            }
        }
    }

    private void releaseReservation(String id, Reservation reservation) {
        reservedBytes.compute(reservation.ledgerKey(), (ignored, total) -> subtract(total, reservation.retained()));
        reservedCounts.compute(reservation.ledgerKey(), (ignored, total) -> subtract(total, 1));
        globalReservedBytes = Math.max(0L, globalReservedBytes - reservation.retained());
        globalReservedCount = Math.max(0, globalReservedCount - 1);
    }

    private void releaseChange(String key, Change change) {
        retainedBytes.compute(key, (ignored, total) -> subtract(total, change.retained()));
        retainedCounts.compute(key, (ignored, total) -> subtract(total, 1));
        globalRetainedBytes = Math.max(0L, globalRetainedBytes - change.retained());
        globalRetainedCount = Math.max(0, globalRetainedCount - 1);
    }

    private static Long subtract(Long total, long amount) {
        long next = Math.max(0L, (total == null ? 0L : total) - amount);
        return next == 0L ? null : next;
    }

    private static Integer subtract(Integer total, int amount) {
        int next = Math.max(0, (total == null ? 0 : total) - amount);
        return next == 0 ? null : next;
    }

    private static String ledgerKey() {
        String session = WorkspaceContext.getOperationRootSession();
        if (session == null || session.isBlank()) session = WorkspaceContext.getSessionOverride();
        if (session == null || session.isBlank()) return null;
        OwnerContext owner = OwnerContext.capture();
        return String.valueOf(owner.tenantId()) + ":" + String.valueOf(owner.userId()) + ":" + session;
    }

    private static String sha256(byte[] bytes) throws IOException {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception failure) { throw new IOException("无法计算文件指纹", failure); }
    }

    private static String jsonEscape(String value) {
        if (value == null) return "未知错误";
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", " ").replace("\n", " ");
    }
}
