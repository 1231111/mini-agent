package com.miniagent.memoryservice.store;

import com.miniagent.common.ErrorCode;
import com.miniagent.common.exception.BusinessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

import static com.miniagent.memoryservice.store.Db.ts;

/** Serializes user-scoped writes against erasure without retaining the raw user id in its tombstone. */
@Repository
public class SubjectLifecycleStore {
    public static final String ACTIVE = "ACTIVE";
    public static final String ERASED = "ERASED";

    public record State(String subjectHash, String status, int erasedItems, int erasedFacts,
                        Instant erasedAt) {
        public boolean erased() {
            return ERASED.equals(status);
        }
    }

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public SubjectLifecycleStore(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Must be called inside the same transaction as the caller's user-scoped write. */
    public void lockForWrite(String tenantId, long userId) {
        State state = lock(tenantId, userId, clock.instant());
        if (state.erased()) {
            throw new BusinessException(ErrorCode.AUTH_FORBIDDEN,
                    "该主体的记忆已完成擦除，拒绝接收迟到的同步数据");
        }
    }

    /** Acquires the lifecycle row lock and returns the prior completion receipt, if any. */
    public Optional<State> lockForErasure(String tenantId, long userId, Instant now) {
        State state = lock(tenantId, userId, now);
        return state.erased() ? Optional.of(state) : Optional.empty();
    }

    /** Persist completion only after every memory-service delete succeeds in the transaction. */
    public void markErased(String tenantId, long userId, int items, int facts, Instant now) {
        jdbc.update("UPDATE mem_subject_erasure SET status = ?, erased_items = ?, erased_facts = ?,"
                        + " erased_at = ?, updated_at = ? WHERE tenant_id = ? AND subject_hash = ?",
                ERASED, items, facts, ts(now), ts(now), tenantId, subjectHash(tenantId, userId));
    }

    public String subjectHash(String tenantId, long userId) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(tenantId.getBytes(StandardCharsets.UTF_8));
            sha.update((byte) 0);
            sha.update(Long.toString(userId).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(sha.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the runtime", impossible);
        }
    }

    private State lock(String tenantId, long userId, Instant now) {
        String hash = subjectHash(tenantId, userId);
        try {
            jdbc.update("INSERT INTO mem_subject_erasure (tenant_id, subject_hash, status,"
                            + " erased_items, erased_facts, updated_at) VALUES (?, ?, ?, 0, 0, ?)",
                    tenantId, hash, ACTIVE, ts(now));
        } catch (DuplicateKeyException alreadyExists) {
            // Existing state is serialized by the following SELECT ... FOR UPDATE.
        }
        return jdbc.query("SELECT subject_hash, status, erased_items, erased_facts, erased_at"
                        + " FROM mem_subject_erasure WHERE tenant_id = ? AND subject_hash = ?"
                        + " FOR UPDATE",
                (rs, row) -> new State(rs.getString("subject_hash"), rs.getString("status"),
                        rs.getInt("erased_items"), rs.getInt("erased_facts"),
                        rs.getTimestamp("erased_at") == null ? null
                                : rs.getTimestamp("erased_at").toInstant()),
                tenantId, hash).stream().findFirst().orElseThrow(() ->
                new IllegalStateException("subject lifecycle row disappeared while locking"));
    }
}
