package com.miniagent.memoryservice.store;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static com.miniagent.memoryservice.store.Db.boolOrNull;
import static com.miniagent.memoryservice.store.Db.instant;
import static com.miniagent.memoryservice.store.Db.longOrNull;
import static com.miniagent.memoryservice.store.Db.ts;

/** 提案。所有状态迁移都按「当前状态」CAS，迁到终态时释放 open_item_id。 */
@Repository
public class ProposalStore {
    /** 触发源（设计文档 §5.5），按优先级从高到低；优先级决定作业领取顺序。 */
    public enum Trigger {
        RECURRENCE, FAILURE, HEALTH, REVALIDATION, OPTIMIZATION, LIFECYCLE;

        public int priority() {
            return ordinal() + 1;
        }
    }

    public record Proposal(long id, String tenantId, long itemId, Trigger trigger,
                           String fingerprint, ProposalState state, String stateReason,
                           long baseRevisionId, Long candidateRevisionId, String candidateHash,
                           String generatorVersion, String evidenceJson, String rationale,
                           String evalJson, Boolean offlinePassed, String experimentSalt,
                           int trafficBps, int maxTrafficBps, int rampStep,
                           Instant stepStartedAt, Instant experimentStartedAt,
                           Instant promotedAt, Instant monitorUntil, String decisionJson,
                           String reviewer, String reviewNote, long tokensUsed,
                           Instant expiresAt, Instant createdAt, Instant updatedAt) {
    }

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    public ProposalStore(JdbcTemplate jdbc, NamedParameterJdbcTemplate named) {
        this.jdbc = jdbc;
        this.named = named;
    }

    /** 条目已有未终结提案时返回空（唯一索引兜底并发）。 */
    public Optional<Long> create(String tenantId, long itemId, Trigger trigger,
                                 String fingerprint, long baseRevisionId, String generatorVersion,
                                 String evidenceJson, Instant now) {
        try {
            return Optional.of(Db.insert(jdbc, "INSERT INTO evo_proposal (tenant_id, item_id,"
                            + " open_item_id, trigger_type, fingerprint, state, base_revision_id,"
                            + " generator_version, evidence_json, traffic_bps, max_traffic_bps,"
                            + " ramp_step, tokens_used, created_at, updated_at)"
                            + " VALUES (?,?,?,?,?,?,?,?,?,0,0,0,0,?,?)",
                    tenantId, itemId, itemId, trigger.name(), fingerprint,
                    ProposalState.DETECTED.name(), baseRevisionId, generatorVersion,
                    evidenceJson, ts(now), ts(now)));
        } catch (DuplicateKeyException alreadyOpen) {
            return Optional.empty();
        }
    }

    public Optional<Proposal> find(long id) {
        return jdbc.query("SELECT * FROM evo_proposal WHERE id = ?", mapper(), id)
                .stream().findFirst();
    }

    public Optional<Proposal> openForItem(long itemId) {
        return jdbc.query("SELECT * FROM evo_proposal WHERE open_item_id = ?", mapper(), itemId)
                .stream().findFirst();
    }

    public boolean move(long id, ProposalState from, ProposalState to, String reason,
                        Instant now) {
        return update(id, from, to, "state_reason = ?", Db.clip(reason, 255), now);
    }

    public boolean candidateReady(long id, long candidateRevisionId, String candidateHash,
                                  String rationale, Instant now) {
        return update(id, ProposalState.GENERATING, ProposalState.EVALUATING,
                "candidate_revision_id = ?, candidate_hash = ?, rationale = ?",
                new Object[]{candidateRevisionId, candidateHash, rationale}, now);
    }

    public void addTokens(long id, long tokens) {
        jdbc.update("UPDATE evo_proposal SET tokens_used = tokens_used + ? WHERE id = ?",
                tokens, id);
    }

    public boolean evaluated(long id, String evalJson, boolean passed, ProposalState to,
                             String reason, Instant expiresAt, Instant now) {
        return update(id, ProposalState.EVALUATING, to,
                "eval_json = ?, offline_passed = ?, state_reason = ?, expires_at = ?",
                new Object[]{evalJson, passed, Db.clip(reason, 255), ts(expiresAt)}, now);
    }

    public boolean startExperiment(long id, ProposalState from, String salt, int bps,
                                   String reviewer, Instant now) {
        return update(id, from, ProposalState.EXPERIMENTING,
                "experiment_salt = ?, traffic_bps = ?, max_traffic_bps = ?, ramp_step = 0,"
                        + " step_started_at = ?, experiment_started_at = ?,"
                        + " reviewer = COALESCE(?, reviewer)",
                new Object[]{salt, bps, bps, ts(now), ts(now), reviewer}, now);
    }

    public boolean ramp(long id, int step, int bps, Instant now) {
        return update(id, ProposalState.EXPERIMENTING, ProposalState.EXPERIMENTING,
                "ramp_step = ?, traffic_bps = ?,"
                        + " max_traffic_bps = CASE WHEN max_traffic_bps < ? THEN ? ELSE max_traffic_bps END,"
                        + " step_started_at = ?",
                new Object[]{step, bps, bps, bps, ts(now)}, now);
    }

    /**
     * 晋升进入监控：监控期继续分流，新修订占 {@code treatmentBps}，旧修订留作同期对照。
     * 未做在线实验（审批直通）的提案在这里生成实验盐。
     */
    public boolean promote(long id, ProposalState from, String salt, int treatmentBps,
                           Instant monitorUntil, String decisionJson, String reviewer,
                           Instant now) {
        return update(id, from, ProposalState.MONITORING,
                "experiment_salt = COALESCE(experiment_salt, ?), traffic_bps = ?,"
                        + " max_traffic_bps = CASE WHEN max_traffic_bps < ? THEN ? ELSE max_traffic_bps END,"
                        + " promoted_at = ?, monitor_until = ?, decision_json = ?,"
                        + " reviewer = COALESCE(?, reviewer)",
                new Object[]{salt, treatmentBps, treatmentBps, treatmentBps, ts(now),
                        ts(monitorUntil), decisionJson, reviewer}, now);
    }

    /** 进入待审批（离线评估之外的入口，例如 ASSISTED 下的归档提案）。 */
    public boolean toApproval(long id, ProposalState from, String reason, Instant expiresAt,
                              Instant now) {
        return update(id, from, ProposalState.APPROVAL_PENDING, "state_reason = ?, expires_at = ?",
                new Object[]{Db.clip(reason, 255), ts(expiresAt)}, now);
    }

    public List<Proposal> expiredApprovals(Instant now, int limit) {
        return jdbc.query("SELECT * FROM evo_proposal WHERE state = ? AND expires_at < ?"
                        + " ORDER BY id LIMIT ?", mapper(), ProposalState.APPROVAL_PENDING.name(),
                ts(now), limit);
    }

    public boolean finish(long id, ProposalState from, ProposalState to, String reason,
                          String decisionJson, Instant now) {
        return update(id, from, to, "state_reason = ?, decision_json = COALESCE(?, decision_json)",
                new Object[]{Db.clip(reason, 255), decisionJson}, now);
    }

    public boolean review(long id, ProposalState from, ProposalState to, String reviewer,
                          String note, Instant now) {
        return update(id, from, to, "reviewer = ?, review_note = ?",
                new Object[]{reviewer, Db.clip(note, 1000)}, now);
    }

    public List<Proposal> forItems(Collection<Long> itemIds, Set<ProposalState> states,
                                   int limit) {
        if (itemIds.isEmpty()) {
            return List.of();
        }
        return named.query("SELECT * FROM evo_proposal WHERE item_id IN (:items)"
                        + " AND state IN (:states) ORDER BY id DESC LIMIT :limit",
                new MapSqlParameterSource("items", itemIds).addValue("states", names(states))
                        .addValue("limit", limit), mapper());
    }

    public List<Proposal> inStates(Set<ProposalState> states, int limit) {
        return named.query("SELECT * FROM evo_proposal WHERE state IN (:states)"
                        + " ORDER BY id LIMIT :limit",
                new MapSqlParameterSource("states", names(states)).addValue("limit", limit),
                mapper());
    }

    public long countOpen(String tenantId) {
        return count("tenant_id = :t AND open_item_id IS NOT NULL",
                new MapSqlParameterSource("t", tenantId));
    }

    /** 冷却：该条目（同指纹，指纹为空则不限）近期以冷却类结局收场过。 */
    public boolean inCooldown(long itemId, String fingerprint, Instant since) {
        MapSqlParameterSource p = new MapSqlParameterSource("i", itemId)
                .addValue("states", names(ProposalState.COOLDOWN)).addValue("since", ts(since));
        String fp = "";
        if (fingerprint != null) {
            fp = " AND fingerprint = :fp";
            p.addValue("fp", fingerprint);
        }
        return count("item_id = :i AND state IN (:states) AND updated_at >= :since" + fp, p) > 0;
    }

    /** 已被否决过的候选内容：生成器再产出同一份内容直接拦下。 */
    public Set<String> rejectedHashes(long itemId) {
        return named.queryForList("SELECT candidate_hash FROM evo_proposal WHERE item_id = :i"
                        + " AND candidate_hash IS NOT NULL AND state IN (:states)",
                new MapSqlParameterSource("i", itemId)
                        .addValue("states", names(ProposalState.COOLDOWN)), String.class)
                .stream().collect(Collectors.toSet());
    }

    /** 窗口内（按创建或晋升时间）处于给定状态的提案数；trigger 为空表示不限触发源。 */
    public long countSince(String tenantId, Set<ProposalState> states, Trigger trigger,
                           Instant since, boolean byPromotion) {
        MapSqlParameterSource p = new MapSqlParameterSource("t", tenantId)
                .addValue("states", names(states)).addValue("since", ts(since));
        String time = byPromotion ? "promoted_at" : "created_at";
        String triggerClause = "";
        if (trigger != null) {
            triggerClause = " AND trigger_type = :tr";
            p.addValue("tr", trigger.name());
        }
        return count("tenant_id = :t AND state IN (:states) AND " + time + " >= :since"
                + triggerClause, p);
    }

    /** 最近 N 个离线通过、且做过在线实验并已有结论的提案（离线-在线一致率）。 */
    public List<Proposal> recentOnlineDecided(String tenantId, int limit) {
        return named.query("SELECT * FROM evo_proposal WHERE tenant_id = :t"
                        + " AND offline_passed = TRUE AND experiment_started_at IS NOT NULL"
                        + " AND state IN (:states) ORDER BY id DESC LIMIT :limit",
                new MapSqlParameterSource("t", tenantId).addValue("limit", limit)
                        .addValue("states", names(Set.of(ProposalState.STABLE,
                                ProposalState.MONITORING, ProposalState.ROLLED_BACK,
                                ProposalState.REJECTED))), mapper());
    }

    /** 已修复指纹：该条目走到 STABLE 的提案所针对的指纹。 */
    public List<String> fixedFingerprints(long itemId) {
        return jdbc.queryForList("SELECT DISTINCT fingerprint FROM evo_proposal WHERE item_id = ?"
                        + " AND state = ? AND fingerprint IS NOT NULL", String.class, itemId,
                ProposalState.STABLE.name());
    }

    /** 晋升出该修订、且仍处于给定状态的提案（用户回退已晋升修订时找它）。 */
    public Optional<Proposal> promotedAs(long candidateRevisionId, ProposalState state) {
        return jdbc.query("SELECT * FROM evo_proposal WHERE candidate_revision_id = ?"
                        + " AND state = ? ORDER BY id DESC LIMIT 1", mapper(), candidateRevisionId,
                state.name()).stream().findFirst();
    }

    public List<Proposal> list(String tenantId, Collection<Long> itemIds, Set<ProposalState> states,
                               int limit) {
        if (itemIds.isEmpty()) {
            return List.of();
        }
        return named.query("SELECT * FROM evo_proposal WHERE tenant_id = :t AND item_id IN (:items)"
                        + " AND state IN (:states) ORDER BY id DESC LIMIT :limit",
                new MapSqlParameterSource("t", tenantId).addValue("items", itemIds)
                        .addValue("states", names(states)).addValue("limit", limit), mapper());
    }

    /** 报表：窗口内按（生成器版本, 状态）计数。 */
    public List<Map<String, Object>> summary(String tenantId, Instant since) {
        String tenant = tenantId == null ? "" : " AND tenant_id = ?";
        Object[] args = tenantId == null ? new Object[]{ts(since)}
                : new Object[]{ts(since), tenantId};
        return jdbc.queryForList("SELECT generator_version, state, COUNT(*) AS proposals,"
                + " SUM(tokens_used) AS tokens FROM evo_proposal WHERE created_at >= ?" + tenant
                + " GROUP BY generator_version, state", args);
    }

    /** 抽检：窗口内最近一个还没人工审过的晋升。 */
    public Optional<Proposal> latestUnreviewedPromotion(Instant since) {
        return jdbc.query("SELECT p.* FROM evo_proposal p WHERE p.promoted_at >= ?"
                        + " AND NOT EXISTS (SELECT 1 FROM evo_review r WHERE r.proposal_id = p.id)"
                        + " ORDER BY p.id DESC LIMIT 1", mapper(), ts(since))
                .stream().findFirst();
    }

    public void deleteForItems(Collection<Long> itemIds) {
        if (!itemIds.isEmpty()) {
            named.update("DELETE FROM evo_proposal WHERE item_id IN (:items)",
                    new MapSqlParameterSource("items", itemIds));
        }
    }

    private boolean update(long id, ProposalState from, ProposalState to, String set,
                           Object value, Instant now) {
        return update(id, from, to, set, new Object[]{value}, now);
    }

    private boolean update(long id, ProposalState from, ProposalState to, String set,
                           Object[] values, Instant now) {
        Object[] args = new Object[values.length + 5];
        System.arraycopy(values, 0, args, 0, values.length);
        args[values.length] = to.name();
        args[values.length + 1] = to.open();
        args[values.length + 2] = ts(now);
        args[values.length + 3] = id;
        args[values.length + 4] = from.name();
        return jdbc.update("UPDATE evo_proposal SET " + set + ", state = ?,"
                + " open_item_id = CASE WHEN ? THEN open_item_id ELSE NULL END, updated_at = ?"
                + " WHERE id = ? AND state = ?", args) == 1;
    }

    private long count(String where, MapSqlParameterSource p) {
        Long n = named.queryForObject("SELECT COUNT(*) FROM evo_proposal WHERE " + where, p,
                Long.class);
        return n == null ? 0 : n;
    }

    private static List<String> names(Set<ProposalState> states) {
        return states.stream().map(Enum::name).toList();
    }

    private static RowMapper<Proposal> mapper() {
        return (rs, n) -> new Proposal(rs.getLong("id"), rs.getString("tenant_id"),
                rs.getLong("item_id"), Trigger.valueOf(rs.getString("trigger_type")),
                rs.getString("fingerprint"),
                ProposalState.valueOf(rs.getString("state")), rs.getString("state_reason"),
                rs.getLong("base_revision_id"), longOrNull(rs, "candidate_revision_id"),
                rs.getString("candidate_hash"), rs.getString("generator_version"),
                rs.getString("evidence_json"), rs.getString("rationale"),
                rs.getString("eval_json"), boolOrNull(rs, "offline_passed"),
                rs.getString("experiment_salt"), rs.getInt("traffic_bps"),
                rs.getInt("max_traffic_bps"), rs.getInt("ramp_step"),
                instant(rs, "step_started_at"), instant(rs, "experiment_started_at"),
                instant(rs, "promoted_at"), instant(rs, "monitor_until"),
                rs.getString("decision_json"), rs.getString("reviewer"),
                rs.getString("review_note"), rs.getLong("tokens_used"),
                instant(rs, "expires_at"), instant(rs, "created_at"), instant(rs, "updated_at"));
    }
}
