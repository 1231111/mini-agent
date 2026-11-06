package com.miniagent.memoryservice.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static com.miniagent.memoryservice.store.Db.ts;

/** 只追加的审计日志 + 回归集 + 人工抽检记录。三者都是「写一次、按条目/时间读」的小表。 */
@Repository
public class AuditStore {
    public static final String REGRESSION_ACTIVE = "ACTIVE";

    public record RegressionCase(long id, long itemId, String fingerprint, String userMessage,
                                 String failureReason) {
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public AuditStore(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void audit(String tenantId, String actor, String action, String targetType,
                      Object targetId, Map<String, ?> detail, Instant now) {
        String detailJson;
        try {
            detailJson = detail == null ? null : json.writeValueAsString(detail);
        } catch (JsonProcessingException e) {
            detailJson = String.valueOf(detail);
        }
        jdbc.update("INSERT INTO audit_log (tenant_id, actor, action, target_type, target_id,"
                        + " detail_json, created_at) VALUES (?,?,?,?,?,?,?)", tenantId,
                Db.clip(actor, 64), action, targetType,
                targetId == null ? null : String.valueOf(targetId), detailJson, ts(now));
    }

    public List<Map<String, Object>> recentAudit(String tenantId, int limit) {
        return jdbc.queryForList("SELECT * FROM audit_log WHERE tenant_id = ?"
                + " ORDER BY id DESC LIMIT ?", tenantId, limit);
    }

    public void addRegressionCase(long itemId, long proposalId, String fingerprint,
                                  String userMessage, String failureReason, int maxPerItem,
                                  Instant now) {
        jdbc.update("INSERT INTO evo_regression_case (item_id, proposal_id, fingerprint,"
                        + " user_message, failure_reason, status, created_at)"
                        + " VALUES (?,?,?,?,?,?,?)", itemId, proposalId, fingerprint, userMessage,
                Db.clip(failureReason, 1000), REGRESSION_ACTIVE, ts(now));
        List<Long> overflow = jdbc.queryForList("SELECT id FROM evo_regression_case"
                        + " WHERE item_id = ? AND status = ? ORDER BY id DESC LIMIT 1000 OFFSET ?",
                Long.class, itemId, REGRESSION_ACTIVE, maxPerItem);
        overflow.forEach(id -> jdbc.update(
                "UPDATE evo_regression_case SET status = 'RETIRED' WHERE id = ?", id));
    }

    public List<RegressionCase> regressionCases(long itemId, int limit) {
        return jdbc.query("SELECT * FROM evo_regression_case WHERE item_id = ? AND status = ?"
                        + " ORDER BY id DESC LIMIT ?",
                (rs, n) -> new RegressionCase(rs.getLong("id"), rs.getLong("item_id"),
                        rs.getString("fingerprint"), rs.getString("user_message"),
                        rs.getString("failure_reason")),
                itemId, REGRESSION_ACTIVE, limit);
    }

    public void deleteRegressionForItems(List<Long> itemIds) {
        itemIds.forEach(id -> jdbc.update("DELETE FROM evo_regression_case WHERE item_id = ?", id));
    }

    public void review(long proposalId, String reviewer, boolean agree, String note,
                       Instant now) {
        jdbc.update("INSERT INTO evo_review (proposal_id, reviewer, agree, note, created_at)"
                        + " VALUES (?,?,?,?,?)", proposalId, Db.clip(reviewer, 64), agree,
                Db.clip(note, 1000), ts(now));
    }

    /** 抽检一致率：[一致数, 总数]。 */
    public long[] reviewAgreement(Instant since) {
        return jdbc.queryForObject("SELECT COUNT(*) AS total,"
                        + " SUM(CASE WHEN agree THEN 1 ELSE 0 END) AS agreed FROM evo_review"
                        + " WHERE created_at >= ?",
                (rs, n) -> new long[]{rs.getLong("agreed"), rs.getLong("total")}, ts(since));
    }
}
