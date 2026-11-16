package com.miniagent.memoryservice.store;

import com.miniagent.memory.contract.MemoryApi.UserFactView;
import com.miniagent.memory.contract.MemoryApi.UserFactMutation;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.miniagent.memoryservice.store.Db.instant;
import static com.miniagent.memoryservice.store.Db.ts;

/** The cloud's canonical snapshot for a user's semantic facts. */
@Repository
public class UserFactStore {
    private final JdbcTemplate jdbc;

    public UserFactStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<UserFactView> list(String tenantId, long userId) {
        return jdbc.query("SELECT subject, predicate, object_value, deleted, revision,"
                        + " source_updated_at, updated_at FROM mem_user_fact"
                        + " WHERE tenant_id = ? AND user_id = ? ORDER BY subject, predicate",
                mapper(), tenantId, userId);
    }

    public Optional<UserFactView> find(String tenantId, long userId,
                                       String subject, String predicate) {
        return jdbc.query("SELECT subject, predicate, object_value, deleted, revision,"
                        + " source_updated_at, updated_at FROM mem_user_fact"
                        + " WHERE tenant_id = ? AND user_id = ? AND subject = ? AND predicate = ?",
                mapper(), tenantId, userId, subject, predicate).stream().findFirst();
    }

    /**
     * Source-time LWW. Existing rows are serialized with SELECT FOR UPDATE. For first-write races,
     * the unique key arbitrates insertion and the loser rereads the canonical row under lock.
     * Equal timestamps use a stable Java comparator, so replay order cannot oscillate the value.
     */
    public void upsert(String tenantId, long userId, UserFactMutation incoming, Instant now) {
        Optional<UserFactView> current = lock(tenantId, userId,
                incoming.subject(), incoming.predicate());
        if (current.isPresent()) {
            apply(tenantId, userId, current.get(), incoming, now);
            return;
        }
        try {
            jdbc.update("INSERT INTO mem_user_fact (tenant_id, user_id, subject, predicate,"
                            + " object_value, deleted, revision, source_updated_at, updated_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?)",
                    tenantId, userId, incoming.subject(), incoming.predicate(), incoming.objectValue(),
                    incoming.deleted(), ts(incoming.sourceUpdatedAt()), ts(now));
        } catch (DuplicateKeyException concurrentFirstWrite) {
            UserFactView winner = lock(tenantId, userId, incoming.subject(), incoming.predicate())
                    .orElseThrow(() -> concurrentFirstWrite);
            apply(tenantId, userId, winner, incoming, now);
        }
    }

    private void apply(String tenantId, long userId, UserFactView current,
                       UserFactMutation incoming, Instant now) {
        if (compare(incoming, current) <= 0) {
            return;
        }
        int count = jdbc.update("UPDATE mem_user_fact SET object_value = ?, deleted = ?,"
                        + " revision = ?, source_updated_at = ?, updated_at = ?"
                        + " WHERE tenant_id = ? AND user_id = ? AND subject = ? AND predicate = ?",
                incoming.objectValue(), incoming.deleted(), current.revision() + 1,
                ts(incoming.sourceUpdatedAt()), ts(now), tenantId, userId,
                incoming.subject(), incoming.predicate());
        if (count != 1) {
            throw new IllegalStateException("user fact changed while its row was locked");
        }
    }

    private static int compare(UserFactMutation incoming, UserFactView current) {
        int byTime = incoming.sourceUpdatedAt().compareTo(current.sourceUpdatedAt());
        if (byTime != 0) return byTime;
        int byDeleted = Boolean.compare(incoming.deleted(), current.deleted());
        if (byDeleted != 0) return byDeleted;
        return incoming.objectValue().compareTo(current.objectValue());
    }

    private Optional<UserFactView> lock(String tenantId, long userId,
                                        String subject, String predicate) {
        return jdbc.query("SELECT subject, predicate, object_value, deleted, revision,"
                        + " source_updated_at, updated_at FROM mem_user_fact"
                        + " WHERE tenant_id = ? AND user_id = ? AND subject = ? AND predicate = ?"
                        + " FOR UPDATE", mapper(), tenantId, userId, subject, predicate)
                .stream().findFirst();
    }

    private static RowMapper<UserFactView> mapper() {
        return (rs, row) -> new UserFactView(rs.getString("subject"),
                rs.getString("predicate"), rs.getString("object_value"),
                rs.getBoolean("deleted"), rs.getLong("revision"),
                instant(rs, "source_updated_at"), instant(rs, "updated_at"));
    }
}
