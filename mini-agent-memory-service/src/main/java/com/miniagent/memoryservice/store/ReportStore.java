package com.miniagent.memoryservice.store;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.miniagent.memoryservice.store.Db.instant;
import static com.miniagent.memoryservice.store.Db.ts;

/** 只读报表查询（设计文档第 13 章的长期 SLO 与看板）。 */
@Repository
public class ReportStore {
    private static final String STABLE = ProposalState.STABLE.name();
    private static final String ROLLED_BACK = ProposalState.ROLLED_BACK.name();

    public record Span(Instant start, Instant end) {
    }

    private final JdbcTemplate jdbc;

    public ReportStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 已修复指纹数（条目 × 指纹）。 */
    public long fixedFingerprints(String tenantId) {
        return count("SELECT COUNT(*) FROM (SELECT DISTINCT item_id, fingerprint FROM evo_proposal"
                + " WHERE tenant_id = ? AND state = ? AND fingerprint IS NOT NULL) t", tenantId, STABLE);
    }

    /** 窗口内触发过复发提案的已修复指纹数。 */
    public long recurredFingerprints(String tenantId, Instant since) {
        return count("SELECT COUNT(*) FROM (SELECT DISTINCT item_id, fingerprint FROM evo_proposal"
                        + " WHERE tenant_id = ? AND trigger_type = ? AND created_at >= ?) t",
                tenantId, ProposalStore.Trigger.RECURRENCE.name(), ts(since));
    }

    /** 自动晋升（无审批人）的失败修复：提案创建 → 晋升的时间段。 */
    public List<Span> autoFixSpans(String tenantId, Instant since) {
        return jdbc.query("SELECT created_at, promoted_at FROM evo_proposal WHERE tenant_id = ?"
                        + " AND promoted_at >= ? AND reviewer IS NULL AND trigger_type IN (?, ?)",
                (rs, n) -> new Span(instant(rs, "created_at"), instant(rs, "promoted_at")),
                tenantId, ts(since), ProposalStore.Trigger.FAILURE.name(),
                ProposalStore.Trigger.RECURRENCE.name());
    }

    /** 活动条目按健康状态计数；tenantId 为空表示全部租户（指标用）。 */
    public Map<String, Long> healthCounts(String tenantId) {
        String tenant = tenantId == null ? "" : " AND tenant_id = ?";
        Object[] args = tenantId == null ? new Object[]{ItemStore.STATUS_ACTIVE}
                : new Object[]{ItemStore.STATUS_ACTIVE, tenantId};
        return grouped("SELECT health AS k, COUNT(*) AS n FROM mem_item WHERE status = ?" + tenant
                + " GROUP BY health", args);
    }

    /** 未终结提案按状态计数（指标用）。 */
    public Map<String, Long> openProposalCounts() {
        return grouped("SELECT state AS k, COUNT(*) AS n FROM evo_proposal"
                + " WHERE open_item_id IS NOT NULL GROUP BY state");
    }

    /** [晋升数, 其中被回滚数]；autoOnly 只看无审批人的自动晋升。 */
    public long[] promotions(String tenantId, Instant since, boolean autoOnly) {
        String auto = autoOnly ? " AND reviewer IS NULL" : "";
        return jdbc.queryForObject("SELECT COUNT(*) AS total,"
                        + " SUM(CASE WHEN state = ? THEN 1 ELSE 0 END) AS rolled FROM evo_proposal"
                        + " WHERE tenant_id = ? AND promoted_at >= ?" + auto,
                (rs, n) -> new long[]{rs.getLong("total"), rs.getLong("rolled")},
                ROLLED_BACK, tenantId, ts(since));
    }

    public long userReverts(String tenantId, Instant since, String reason) {
        return count("SELECT COUNT(*) FROM evo_proposal WHERE tenant_id = ? AND promoted_at >= ?"
                + " AND state = ? AND state_reason = ?", tenantId, ts(since), ROLLED_BACK, reason);
    }

    /** [token 总量, 进入 STABLE 的提案数]。 */
    public long[] costPerStable(String tenantId, Instant since) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(tokens_used), 0) AS tokens,"
                        + " SUM(CASE WHEN state = ? THEN 1 ELSE 0 END) AS stable FROM evo_proposal"
                        + " WHERE tenant_id = ? AND created_at >= ?",
                (rs, n) -> new long[]{rs.getLong("tokens"), rs.getLong("stable")},
                STABLE, tenantId, ts(since));
    }

    private Map<String, Long> grouped(String sql, Object... args) {
        Map<String, Long> counts = new HashMap<>();
        jdbc.query(sql, rs -> {
            counts.put(rs.getString("k"), rs.getLong("n"));
        }, args);
        return counts;
    }

    private long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }
}
