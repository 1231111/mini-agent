package com.miniagent.memoryservice.store;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.miniagent.memoryservice.store.Db.longOrNull;
import static com.miniagent.memoryservice.store.Db.ts;

/**
 * 结果事件与聚合。成败一律按「任务」计：同一任务内任一计数事件失败（或用户否定）即该任务失败，
 * 因为随机化单位是任务，节点数不同的任务不能按节点加权。
 *
 * <p>ponytail: 聚合直接扫 outcome_event（有 revision/proposal/item 索引）；
 * 单条记忆日事件量上万后再加按日汇总表。</p>
 */
@Repository
public class OutcomeStore {
    public static final String FAILURE = "FAILURE";
    private static final String FAILED_CASE = "CASE WHEN result IN ('FAILURE','FEEDBACK_DOWN')"
            + " THEN 1 ELSE 0 END";
    private static final String CASE_SELECT =
            "SELECT id, user_id, task_key, detail_json, fingerprint FROM outcome_event";

    public record Event(String eventId, String tenantId, long userId, String projectId,
                        long itemId, long revisionId, Long proposalId, String arm,
                        boolean armValid, boolean holdout, String taskKey, String nodeKey,
                        String result, boolean counted, String errorCode, String fingerprint,
                        String detailJson, String envVersion, Instant occurredAt,
                        Instant receivedAt) {
    }

    /** 任务级成败计数。 */
    public record Counts(long tasks, long successes) {
        public static final Counts EMPTY = new Counts(0, 0);

        public long failures() {
            return tasks - successes;
        }

        public double successRate() {
            return tasks == 0 ? 0 : (double) successes / tasks;
        }
    }

    public record Cluster(String fingerprint, long tasks) {
    }

    public record CaseRow(long eventId, long userId, String taskKey, String detailJson,
                          String fingerprint) {
    }

    public record Target(long itemId, long revisionId, Long proposalId, String arm,
                         boolean armValid, String projectId) {
    }

    private final JdbcTemplate jdbc;

    public OutcomeStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 返回 false 表示同一 eventId 已收过（幂等重放）。 */
    public boolean insert(Event e) {
        try {
            jdbc.update("INSERT INTO outcome_event (event_id, tenant_id, user_id, project_id,"
                            + " item_id, revision_id, proposal_id, arm, arm_valid, holdout, task_key,"
                            + " node_key, result, counted, error_code, fingerprint, detail_json,"
                            + " env_version, occurred_at, received_at)"
                            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    e.eventId(), e.tenantId(), e.userId(), e.projectId(), e.itemId(),
                    e.revisionId(), e.proposalId(), e.arm(), e.armValid(), e.holdout(),
                    e.taskKey(), e.nodeKey(), e.result(), e.counted(), e.errorCode(),
                    e.fingerprint(), e.detailJson(), e.envVersion(), ts(e.occurredAt()),
                    ts(e.receivedAt()));
            return true;
        } catch (DuplicateKeyException duplicate) {
            return false;
        }
    }

    /** 实验各臂（BASELINE / CANDIDATE）自 since 起的任务级成败；只计分臂校验通过的事件。 */
    public Map<String, Counts> armStats(long proposalId, Instant since) {
        Map<String, Counts> result = new HashMap<>();
        jdbc.query("SELECT arm, COUNT(*) AS tasks, SUM(CASE WHEN fails = 0 THEN 1 ELSE 0 END) AS ok"
                        + " FROM (SELECT arm, task_key, SUM(" + FAILED_CASE + ") AS fails"
                        + " FROM outcome_event WHERE proposal_id = ? AND arm_valid = TRUE"
                        + " AND counted = TRUE AND occurred_at >= ? GROUP BY arm, task_key) t"
                        + " GROUP BY arm",
                rs -> {
                    result.put(rs.getString("arm"),
                            new Counts(rs.getLong("tasks"), rs.getLong("ok")));
                }, proposalId, ts(since));
        return result;
    }

    public Counts revisionStats(long revisionId, Instant from, Instant to) {
        return stats("revision_id = ?", revisionId, from, to);
    }

    public Counts itemStats(long itemId, Instant from, Instant to) {
        return stats("item_id = ?", itemId, from, to);
    }

    /** 当前修订上的失败簇（按指纹聚合，计任务数）。 */
    public List<Cluster> clusters(long revisionId, Instant since) {
        return jdbc.query("SELECT fingerprint, COUNT(DISTINCT task_key) AS tasks"
                        + " FROM outcome_event WHERE revision_id = ? AND result = 'FAILURE'"
                        + " AND counted = TRUE AND fingerprint IS NOT NULL AND occurred_at >= ?"
                        + " GROUP BY fingerprint ORDER BY tasks DESC",
                (rs, n) -> new Cluster(rs.getString("fingerprint"), rs.getLong("tasks")),
                revisionId, ts(since));
    }

    /** 当前修订最近 N 个任务中失败任务的占比；不足 N 个按实际数计算。 */
    public Counts recentTasks(long revisionId, int limit) {
        List<Integer> failed = jdbc.queryForList("SELECT MAX(" + FAILED_CASE + ") AS failed"
                        + " FROM outcome_event WHERE revision_id = ? AND counted = TRUE"
                        + " GROUP BY task_key ORDER BY MAX(occurred_at) DESC LIMIT ?",
                Integer.class, revisionId, limit);
        long failures = failed.stream().filter(f -> f != null && f > 0).count();
        return new Counts(failed.size(), failed.size() - failures);
    }

    public long negatives(long revisionId, Instant since) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM outcome_event WHERE revision_id = ?"
                + " AND result = 'FEEDBACK_DOWN' AND occurred_at >= ?", Long.class,
                revisionId, ts(since));
        return count == null ? 0 : count;
    }

    /** 某指纹的失败案例，每个任务取最新一条。 */
    public List<CaseRow> failureCases(long revisionId, String fingerprint, Instant since,
                                      int limit) {
        return jdbc.query(CASE_SELECT
                        + " WHERE id IN (SELECT MAX(id) FROM outcome_event WHERE revision_id = ?"
                        + " AND fingerprint = ? AND result = 'FAILURE' AND counted = TRUE"
                        + " AND detail_json IS NOT NULL AND occurred_at >= ? GROUP BY task_key)"
                        + " ORDER BY id DESC LIMIT ?",
                caseMapper(), revisionId, fingerprint, ts(since), limit);
    }

    /** 修订上的失败案例（不限指纹），健康分退化 / 优化扫描用。 */
    public List<CaseRow> anyFailureCases(long revisionId, Instant since, int limit) {
        return jdbc.query(CASE_SELECT
                        + " WHERE id IN (SELECT MAX(id) FROM outcome_event WHERE revision_id = ?"
                        + " AND result = 'FAILURE' AND counted = TRUE AND detail_json IS NOT NULL"
                        + " AND occurred_at >= ? GROUP BY task_key) ORDER BY id DESC LIMIT ?",
                caseMapper(), revisionId, ts(since), limit);
    }

    /** 修订上全程无失败的任务，每个任务取最新一条。 */
    public List<CaseRow> successCases(long revisionId, Instant since, int limit) {
        return jdbc.query(CASE_SELECT
                        + " WHERE id IN (SELECT MAX(id) FROM outcome_event WHERE revision_id = ?"
                        + " AND counted = TRUE AND detail_json IS NOT NULL AND occurred_at >= ?"
                        + " GROUP BY task_key HAVING SUM(" + FAILED_CASE + ") = 0)"
                        + " ORDER BY id DESC LIMIT ?",
                caseMapper(), revisionId, ts(since), limit);
    }

    public long recurrences(long revisionId, String fingerprint, Instant since) {
        Long count = jdbc.queryForObject("SELECT COUNT(DISTINCT task_key) FROM outcome_event"
                + " WHERE revision_id = ? AND fingerprint = ? AND result = 'FAILURE'"
                + " AND counted = TRUE AND occurred_at >= ?", Long.class,
                revisionId, fingerprint, ts(since));
        return count == null ? 0 : count;
    }

    /** 实验 / 监控期间某臂上该指纹再次出现的任务数（护栏「本提案针对的指纹不复现」）。 */
    public long armRecurrences(long proposalId, String arm, String fingerprint, Instant since) {
        Long count = jdbc.queryForObject("SELECT COUNT(DISTINCT task_key) FROM outcome_event"
                + " WHERE proposal_id = ? AND arm = ? AND arm_valid = TRUE AND fingerprint = ?"
                + " AND result = 'FAILURE' AND counted = TRUE AND occurred_at >= ?", Long.class,
                proposalId, arm, fingerprint, ts(since));
        return count == null ? 0 : count;
    }

    /** 租户（为空则全部）近期按任务数排序的条目 id：重验先验使用量大的。 */
    public List<Long> topItems(String tenantId, Instant since, int limit) {
        String tenant = tenantId == null ? "" : " AND tenant_id = ?";
        Object[] args = tenantId == null ? new Object[]{ts(since), limit}
                : new Object[]{ts(since), tenantId, limit};
        return jdbc.queryForList("SELECT item_id FROM outcome_event WHERE occurred_at >= ?"
                + tenant + " GROUP BY item_id ORDER BY COUNT(DISTINCT task_key) DESC LIMIT ?",
                Long.class, args);
    }

    /** 用户反馈落到该任务用过的每个（条目, 修订, 臂）上。 */
    public List<Target> taskTargets(String tenantId, long userId, String taskKey) {
        return jdbc.query("SELECT DISTINCT item_id, revision_id, proposal_id, arm, arm_valid,"
                        + " project_id FROM outcome_event WHERE tenant_id = ? AND user_id = ?"
                        + " AND task_key = ? AND result IN ('SUCCESS','FAILURE')",
                (rs, n) -> new Target(rs.getLong("item_id"), rs.getLong("revision_id"),
                        longOrNull(rs, "proposal_id"), rs.getString("arm"),
                        rs.getBoolean("arm_valid"), rs.getString("project_id")),
                tenantId, userId, taskKey);
    }

    public long distinctTasks(long itemId, Instant since) {
        Long count = jdbc.queryForObject("SELECT COUNT(DISTINCT task_key) FROM outcome_event"
                + " WHERE item_id = ? AND occurred_at >= ?", Long.class, itemId, ts(since));
        return count == null ? 0 : count;
    }

    /** 该修订自 since 起跑得最多的环境版本；没有带环境的事件时为 null。 */
    public String dominantEnv(long revisionId, Instant since) {
        return jdbc.queryForList("SELECT env_version FROM outcome_event WHERE revision_id = ?"
                        + " AND occurred_at >= ? AND env_version IS NOT NULL"
                        + " GROUP BY env_version ORDER BY COUNT(*) DESC LIMIT 1", String.class,
                revisionId, ts(since)).stream().findFirst().orElse(null);
    }

    /** 留存组 vs 非留存组的任务级成败（租户维度）。 */
    public Map<Boolean, Counts> holdoutStats(String tenantId, Instant since) {
        Map<Boolean, Counts> result = new HashMap<>();
        jdbc.query("SELECT holdout, COUNT(*) AS tasks, SUM(CASE WHEN fails = 0 THEN 1 ELSE 0 END)"
                        + " AS ok FROM (SELECT holdout, task_key, SUM(" + FAILED_CASE + ") AS fails"
                        + " FROM outcome_event WHERE tenant_id = ? AND counted = TRUE"
                        + " AND occurred_at >= ? GROUP BY holdout, task_key) t GROUP BY holdout",
                rs -> {
                    result.put(rs.getBoolean("holdout"),
                            new Counts(rs.getLong("tasks"), rs.getLong("ok")));
                }, tenantId, ts(since));
        return result;
    }

    public int purge(Instant before) {
        return jdbc.update("DELETE FROM outcome_event WHERE received_at < ?", ts(before));
    }

    public int deleteUser(String tenantId, long userId) {
        return jdbc.update("DELETE FROM outcome_event WHERE tenant_id = ? AND user_id = ?",
                tenantId, userId);
    }

    private Counts stats(String filter, long id, Instant from, Instant to) {
        List<Counts> rows = jdbc.query("SELECT COUNT(*) AS tasks,"
                        + " SUM(CASE WHEN fails = 0 THEN 1 ELSE 0 END) AS ok FROM (SELECT task_key,"
                        + " SUM(" + FAILED_CASE + ") AS fails FROM outcome_event WHERE " + filter
                        + " AND counted = TRUE AND occurred_at >= ? AND occurred_at < ?"
                        + " GROUP BY task_key) t",
                (rs, n) -> new Counts(rs.getLong("tasks"), rs.getLong("ok")), id, ts(from), ts(to));
        return rows.isEmpty() ? Counts.EMPTY : rows.getFirst();
    }

    private static RowMapper<CaseRow> caseMapper() {
        return (rs, n) -> new CaseRow(rs.getLong("id"), rs.getLong("user_id"),
                rs.getString("task_key"), rs.getString("detail_json"), rs.getString("fingerprint"));
    }
}
