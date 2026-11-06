package com.miniagent.memoryservice.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.memory.contract.MemoryApi.ScopeType;
import com.miniagent.memory.contract.ProcedureContent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static com.miniagent.memoryservice.store.Db.instant;
import static com.miniagent.memoryservice.store.Db.longOrNull;
import static com.miniagent.memoryservice.store.Db.ts;

/** 条目、不可变修订、激活指针（CAS）与激活历史。 */
@Repository
public class ItemStore {
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_ARCHIVED = "ARCHIVED";
    public static final String STATUS_DELETED = "DELETED";
    public static final String HEALTH_UNKNOWN = "UNKNOWN";
    public static final String HEALTH_HEALTHY = "HEALTHY";
    public static final String HEALTH_DEGRADED = "DEGRADED";

    /** 修订来源。 */
    public enum Origin { USER_EDIT, IMPORT, EVOLUTION }

    /** 激活历史原因；ARCHIVE / RESTORE 不换修订，只记录状态变化。 */
    public enum Reason {
        CREATE, USER_EDIT, IMPORT, PROMOTE, ROLLBACK, USER_REVERT, ADMIN_ROLLBACK, ARCHIVE, RESTORE;

        /** 进化引起的激活（晋升与其回滚）对留存组不可见。 */
        public boolean evolution() {
            return this == PROMOTE || this == ROLLBACK || this == ADMIN_ROLLBACK;
        }
    }

    public record Item(long id, String tenantId, ScopeType scopeType, String scopeId,
                       long ownerUserId, String name, String status, Long activeRevisionId,
                       Long holdoutRevisionId, Integer holdoutPeriod, long activationVersion,
                       boolean evolutionLocked, String health, Long legacyProcedureId,
                       String validatedEnvVersion, Instant lastUsedAt, Instant createdAt,
                       Instant updatedAt) {
        /** 本周期还没有任何激活时，周期开始时的修订就是当前激活修订。 */
        public Long holdoutRevision(int currentPeriod) {
            return holdoutPeriod != null && holdoutPeriod == currentPeriod
                    ? holdoutRevisionId : activeRevisionId;
        }
    }

    public record Revision(long id, long itemId, int revisionNo, Long parentRevisionId,
                           String origin, ProcedureContent content, String contentHash,
                           Long proposalId, String createdBy, Instant createdAt) {
    }

    public record Activation(long id, long itemId, String itemName, Integer fromRevisionNo,
                             int toRevisionNo, String reason, Long proposalId, String actor,
                             Instant createdAt) {
    }

    private static final String ACTIVATION_SELECT = "SELECT a.id, a.item_id, i.name AS item_name,"
            + " rf.revision_no AS from_no, rt.revision_no AS to_no, a.reason, a.proposal_id,"
            + " a.actor, a.created_at FROM mem_activation a JOIN mem_item i ON i.id = a.item_id"
            + " JOIN mem_revision rt ON rt.id = a.to_revision_id"
            + " LEFT JOIN mem_revision rf ON rf.id = a.from_revision_id ";

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final ObjectMapper json;

    public ItemStore(JdbcTemplate jdbc, NamedParameterJdbcTemplate named, ObjectMapper json) {
        this.jdbc = jdbc;
        this.named = named;
        this.json = json;
    }

    public static String nameKey(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }

    public Optional<Item> find(long id) {
        return jdbc.query("SELECT * FROM mem_item WHERE id = ?", itemMapper(), id)
                .stream().findFirst();
    }

    /** 行锁：修订号分配与激活在同一事务内串行。 */
    public Optional<Item> lock(long id) {
        return jdbc.query("SELECT * FROM mem_item WHERE id = ? FOR UPDATE", itemMapper(), id)
                .stream().findFirst();
    }

    public Optional<Item> findByName(String tenantId, ScopeType scopeType, String scopeId,
                                     String name) {
        return jdbc.query("SELECT * FROM mem_item WHERE tenant_id = ? AND scope_type = ?"
                        + " AND scope_id = ? AND name_key = ?", itemMapper(),
                tenantId, scopeType.name(), scopeId, nameKey(name)).stream().findFirst();
    }

    public Optional<Item> findByLegacy(String tenantId, long legacyProcedureId) {
        return jdbc.query("SELECT * FROM mem_item WHERE tenant_id = ? AND legacy_procedure_id = ?",
                itemMapper(), tenantId, legacyProcedureId).stream().findFirst();
    }

    public long insertItem(String tenantId, ScopeType scopeType, String scopeId, long ownerUserId,
                           String name, Long legacyProcedureId, Instant now) {
        return Db.insert(jdbc, "INSERT INTO mem_item (tenant_id, scope_type, scope_id,"
                        + " owner_user_id, name, name_key, status, activation_version,"
                        + " evolution_locked, health, legacy_procedure_id, created_at, updated_at)"
                        + " VALUES (?,?,?,?,?,?,?,0,FALSE,'UNKNOWN',?,?,?)",
                tenantId, scopeType.name(), scopeId, ownerUserId, name.trim(), nameKey(name),
                STATUS_ACTIVE, legacyProcedureId, ts(now), ts(now));
    }

    /** 调用方须已在同一事务里 {@link #lock} 住条目，修订号才不会并发冲突。 */
    public Revision insertRevision(long itemId, Long parentRevisionId, Origin origin,
                                   ProcedureContent content, Long proposalId, String createdBy,
                                   Instant now) {
        Integer max = jdbc.queryForObject(
                "SELECT MAX(revision_no) FROM mem_revision WHERE item_id = ?", Integer.class, itemId);
        int revisionNo = max == null ? 1 : max + 1;
        String hash = content.hash();
        long id = Db.insert(jdbc, "INSERT INTO mem_revision (item_id, revision_no,"
                        + " parent_revision_id, origin, content_json, content_hash, proposal_id,"
                        + " created_by, created_at) VALUES (?,?,?,?,?,?,?,?,?)",
                itemId, revisionNo, parentRevisionId, origin.name(), write(content), hash,
                proposalId, createdBy, ts(now));
        return new Revision(id, itemId, revisionNo, parentRevisionId, origin.name(), content,
                hash, proposalId, createdBy, now);
    }

    public Optional<Revision> revision(Long id) {
        if (id == null) {
            return Optional.empty();
        }
        return jdbc.query("SELECT * FROM mem_revision WHERE id = ?", revisionMapper(), id)
                .stream().findFirst();
    }

    public Optional<Revision> revisionByNo(long itemId, int revisionNo) {
        return jdbc.query("SELECT * FROM mem_revision WHERE item_id = ? AND revision_no = ?",
                revisionMapper(), itemId, revisionNo).stream().findFirst();
    }

    public List<Revision> revisions(long itemId) {
        return jdbc.query("SELECT * FROM mem_revision WHERE item_id = ? ORDER BY revision_no DESC",
                revisionMapper(), itemId);
    }

    /**
     * 按激活版本 CAS 切换激活修订并写历史。返回 false 表示有人先改了（调用方决定重试或放弃）。
     * 同时维护留存组修订：人工激活留存组跟随；进化激活留存组停在本周期开始时的修订。
     *
     * <p>SET 顺序有意为之：MySQL 单表 UPDATE 从左到右求值、后面的表达式读到前面赋的新值，
     * 所以读旧值的留存列必须写在 active_revision_id / holdout_period 赋值之前。</p>
     */
    public boolean activate(Item item, long toRevisionId, Reason reason, Long proposalId,
                            String actor, int holdoutPeriod, Instant now) {
        int updated = jdbc.update("UPDATE mem_item SET holdout_revision_id = CASE WHEN ? THEN ?"
                        + " WHEN holdout_period IS NULL OR holdout_period <> ? THEN active_revision_id"
                        + " ELSE holdout_revision_id END, holdout_period = ?,"
                        + " active_revision_id = ?, activation_version = activation_version + 1,"
                        + " status = ?, updated_at = ? WHERE id = ? AND activation_version = ?",
                !reason.evolution(), toRevisionId, holdoutPeriod, holdoutPeriod, toRevisionId,
                STATUS_ACTIVE, ts(now), item.id(), item.activationVersion());
        if (updated != 1) {
            return false;
        }
        history(item.id(), item.activeRevisionId(), toRevisionId, reason, proposalId, actor,
                item.activationVersion() + 1, now);
        return true;
    }

    /** 归档 / 恢复 / 删除：只改状态并记一条历史，激活修订不变。 */
    public boolean changeStatus(Item item, String status, Reason reason, String actor,
                                Instant now) {
        int updated = jdbc.update("UPDATE mem_item SET status = ?,"
                        + " activation_version = activation_version + 1, updated_at = ?"
                        + " WHERE id = ? AND activation_version = ?",
                status, ts(now), item.id(), item.activationVersion());
        if (updated != 1) {
            return false;
        }
        if (item.activeRevisionId() != null) {
            history(item.id(), item.activeRevisionId(), item.activeRevisionId(), reason, null,
                    actor, item.activationVersion() + 1, now);
        }
        return true;
    }

    public void setLocked(long id, boolean locked, Instant now) {
        jdbc.update("UPDATE mem_item SET evolution_locked = ?, updated_at = ? WHERE id = ?",
                locked, ts(now), id);
    }

    public void setHealth(long id, String health) {
        jdbc.update("UPDATE mem_item SET health = ? WHERE id = ?", health, id);
    }

    public void setValidatedEnv(long id, String envVersion) {
        jdbc.update("UPDATE mem_item SET validated_env_version = ? WHERE id = ?", envVersion, id);
    }

    public void touch(Collection<Long> ids, Instant now) {
        if (ids.isEmpty()) {
            return;
        }
        named.update("UPDATE mem_item SET last_used_at = :now WHERE id IN (:ids)",
                new MapSqlParameterSource("now", ts(now)).addValue("ids", ids));
    }

    /** 用户可见的条目：自己的 USER 域 + 有授权的 PROJECT 域。 */
    public List<Item> visible(String tenantId, long userId, Collection<String> projectIds,
                              String nameLike, Long afterId, int limit) {
        MapSqlParameterSource p = new MapSqlParameterSource("t", tenantId)
                .addValue("u", String.valueOf(userId)).addValue("limit", limit)
                .addValue("s", STATUS_DELETED);
        StringBuilder sql = new StringBuilder("SELECT * FROM mem_item WHERE tenant_id = :t"
                + " AND status <> :s AND ((scope_type = 'USER' AND scope_id = :u)");
        if (!projectIds.isEmpty()) {
            sql.append(" OR (scope_type = 'PROJECT' AND scope_id IN (:p))");
            p.addValue("p", projectIds);
        }
        sql.append(')');
        if (nameLike != null && !nameLike.isBlank()) {
            sql.append(" AND name_key LIKE :n");
            p.addValue("n", "%" + nameKey(nameLike) + "%");
        }
        if (afterId != null) {
            sql.append(" AND id > :after");
            p.addValue("after", afterId);
        }
        sql.append(" ORDER BY id LIMIT :limit");
        return named.query(sql.toString(), p, itemMapper());
    }

    /** 组装上下文的候选：当前用户 USER 域 + 当前项目 PROJECT 域，最近使用优先。 */
    public List<Item> forContext(String tenantId, long userId, String projectId, int limit) {
        MapSqlParameterSource p = new MapSqlParameterSource("t", tenantId)
                .addValue("u", String.valueOf(userId)).addValue("s", STATUS_ACTIVE)
                .addValue("limit", limit);
        String projectClause = "";
        if (projectId != null) {
            projectClause = " OR (scope_type = 'PROJECT' AND scope_id = :p)";
            p.addValue("p", projectId);
        }
        return named.query("SELECT * FROM mem_item WHERE tenant_id = :t AND status = :s"
                + " AND active_revision_id IS NOT NULL AND ((scope_type = 'USER' AND scope_id = :u)"
                + projectClause + ") ORDER BY last_used_at DESC, id DESC LIMIT :limit", p,
                itemMapper());
    }

    public List<Item> byStatus(String status, Long afterId, int limit) {
        return jdbc.query("SELECT * FROM mem_item WHERE status = ? AND id > ? ORDER BY id LIMIT ?",
                itemMapper(), status, afterId == null ? 0L : afterId, limit);
    }

    public List<Activation> history(long itemId, int limit) {
        return jdbc.query(ACTIVATION_SELECT + "WHERE a.item_id = ? ORDER BY a.id DESC LIMIT ?",
                activationMapper(), itemId, limit);
    }

    public List<Activation> changes(String tenantId, long userId, Collection<String> projectIds,
                                    Instant since, int limit) {
        MapSqlParameterSource p = new MapSqlParameterSource("t", tenantId)
                .addValue("u", String.valueOf(userId)).addValue("since", ts(since))
                .addValue("limit", limit);
        StringBuilder sql = new StringBuilder(ACTIVATION_SELECT).append("WHERE i.tenant_id = :t"
                + " AND a.created_at >= :since AND ((i.scope_type = 'USER' AND i.scope_id = :u)");
        if (!projectIds.isEmpty()) {
            sql.append(" OR (i.scope_type = 'PROJECT' AND i.scope_id IN (:p))");
            p.addValue("p", projectIds);
        }
        sql.append(") ORDER BY a.id DESC LIMIT :limit");
        return named.query(sql.toString(), p, activationMapper());
    }

    /** 某时间点之后由进化晋升、且仍处于激活状态的条目（运维批量回滚用）。 */
    public List<Activation> promotionsSince(String tenantId, Instant since) {
        String tenantClause = tenantId == null ? "" : " AND i.tenant_id = ?";
        Object[] args = tenantId == null ? new Object[]{ts(since)}
                : new Object[]{ts(since), tenantId};
        return jdbc.query(ACTIVATION_SELECT + "WHERE a.reason = 'PROMOTE' AND a.created_at >= ?"
                + tenantClause + " AND i.active_revision_id = a.to_revision_id"
                + " ORDER BY a.id", activationMapper(), args);
    }

    /** 用户删除级联：返回被删条目 id，供调用方清理提案与作业。 */
    public List<Long> deleteUserScope(String tenantId, long userId) {
        List<Long> ids = jdbc.queryForList("SELECT id FROM mem_item WHERE tenant_id = ?"
                + " AND scope_type = 'USER' AND scope_id = ?", Long.class, tenantId,
                String.valueOf(userId));
        if (ids.isEmpty()) {
            return ids;
        }
        MapSqlParameterSource p = new MapSqlParameterSource("ids", ids);
        named.update("DELETE FROM mem_activation WHERE item_id IN (:ids)", p);
        named.update("DELETE FROM mem_revision WHERE item_id IN (:ids)", p);
        named.update("DELETE FROM mem_item WHERE id IN (:ids)", p);
        return ids;
    }

    private void history(long itemId, Long fromRevisionId, long toRevisionId, Reason reason,
                         Long proposalId, String actor, long version, Instant now) {
        jdbc.update("INSERT INTO mem_activation (item_id, from_revision_id, to_revision_id,"
                        + " reason, proposal_id, actor, activation_version, created_at)"
                        + " VALUES (?,?,?,?,?,?,?,?)",
                itemId, fromRevisionId, toRevisionId, reason.name(), proposalId,
                Db.clip(actor, 64), version, ts(now));
    }

    private String write(ProcedureContent content) {
        try {
            return json.writeValueAsString(content);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialize procedure content", e);
        }
    }

    private ProcedureContent read(String text) {
        try {
            return json.readValue(text, ProcedureContent.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("corrupt procedure content", e);
        }
    }

    private static RowMapper<Item> itemMapper() {
        return (rs, n) -> new Item(rs.getLong("id"), rs.getString("tenant_id"),
                ScopeType.valueOf(rs.getString("scope_type")), rs.getString("scope_id"),
                rs.getLong("owner_user_id"), rs.getString("name"), rs.getString("status"),
                longOrNull(rs, "active_revision_id"), longOrNull(rs, "holdout_revision_id"),
                Db.intOrNull(rs, "holdout_period"),
                rs.getLong("activation_version"), rs.getBoolean("evolution_locked"),
                rs.getString("health"), longOrNull(rs, "legacy_procedure_id"),
                rs.getString("validated_env_version"), instant(rs, "last_used_at"),
                instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private RowMapper<Revision> revisionMapper() {
        return (rs, n) -> new Revision(rs.getLong("id"), rs.getLong("item_id"),
                rs.getInt("revision_no"), longOrNull(rs, "parent_revision_id"),
                rs.getString("origin"), read(rs.getString("content_json")),
                rs.getString("content_hash"), longOrNull(rs, "proposal_id"),
                rs.getString("created_by"), instant(rs, "created_at"));
    }

    private static RowMapper<Activation> activationMapper() {
        return (rs, n) -> new Activation(rs.getLong("id"), rs.getLong("item_id"),
                rs.getString("item_name"), Db.intOrNull(rs, "from_no"), rs.getInt("to_no"),
                rs.getString("reason"), longOrNull(rs, "proposal_id"), rs.getString("actor"),
                instant(rs, "created_at"));
    }
}
