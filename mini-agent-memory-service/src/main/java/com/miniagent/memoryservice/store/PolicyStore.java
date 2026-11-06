package com.miniagent.memoryservice.store;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static com.miniagent.memoryservice.store.Db.intOrNull;
import static com.miniagent.memoryservice.store.Db.longOrNull;
import static com.miniagent.memoryservice.store.Db.ts;

/**
 * 租户进化策略与每日 token 用量。{@code tenant_id = '*'} 的行是全局冻结开关。
 *
 * <p>ponytail: 每次读策略直接查主键，不做进程内缓存；QPS 上来再加 {@code policy.cache-seconds}。</p>
 */
@Repository
public class PolicyStore {
    public static final String GLOBAL = "*";

    public record Policy(String tenantId, String mode, boolean frozen, String frozenReason,
                         Long dailyTokenBudget, Integer holdoutBps) {
    }

    private final JdbcTemplate jdbc;

    public PolicyStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Policy> find(String tenantId) {
        return jdbc.query("SELECT * FROM evo_tenant_policy WHERE tenant_id = ?",
                (rs, n) -> new Policy(rs.getString("tenant_id"), rs.getString("mode"),
                        rs.getBoolean("frozen"), rs.getString("frozen_reason"),
                        longOrNull(rs, "daily_token_budget"), intOrNull(rs, "holdout_bps")),
                tenantId).stream().findFirst();
    }

    /** 只覆盖非空字段。 */
    public void upsert(String tenantId, String mode, Long dailyTokenBudget, Integer holdoutBps,
                       String actor, Instant now) {
        int updated = jdbc.update("UPDATE evo_tenant_policy SET mode = COALESCE(?, mode),"
                        + " daily_token_budget = COALESCE(?, daily_token_budget),"
                        + " holdout_bps = COALESCE(?, holdout_bps), updated_by = ?, updated_at = ?"
                        + " WHERE tenant_id = ?", mode, dailyTokenBudget, holdoutBps, actor,
                ts(now), tenantId);
        if (updated == 0) {
            insertIgnoringRace(tenantId, mode, false, null, dailyTokenBudget, holdoutBps, actor,
                    now);
        }
    }

    public void setFrozen(String tenantId, boolean frozen, String reason, String actor,
                          Instant now) {
        int updated = jdbc.update("UPDATE evo_tenant_policy SET frozen = ?, frozen_reason = ?,"
                        + " updated_by = ?, updated_at = ? WHERE tenant_id = ?", frozen,
                Db.clip(reason, 255), actor, ts(now), tenantId);
        if (updated == 0) {
            insertIgnoringRace(tenantId, null, frozen, reason, null, null, actor, now);
        }
    }

    public List<String> tenants() {
        return jdbc.queryForList("SELECT DISTINCT tenant_id FROM mem_item", String.class);
    }

    public long usedTokens(String tenantId, LocalDate day) {
        List<Long> rows = jdbc.queryForList("SELECT tokens FROM evo_budget_usage"
                + " WHERE tenant_id = ? AND usage_date = ?", Long.class, tenantId, day);
        return rows.isEmpty() ? 0 : rows.getFirst();
    }

    public void addTokens(String tenantId, LocalDate day, long tokens) {
        if (tokens <= 0) {
            return;
        }
        int updated = jdbc.update("UPDATE evo_budget_usage SET tokens = tokens + ?"
                + " WHERE tenant_id = ? AND usage_date = ?", tokens, tenantId, day);
        if (updated == 0) {
            try {
                jdbc.update("INSERT INTO evo_budget_usage (tenant_id, usage_date, tokens)"
                        + " VALUES (?,?,?)", tenantId, day, tokens);
            } catch (DuplicateKeyException race) {
                jdbc.update("UPDATE evo_budget_usage SET tokens = tokens + ?"
                        + " WHERE tenant_id = ? AND usage_date = ?", tokens, tenantId, day);
            }
        }
    }

    private void insertIgnoringRace(String tenantId, String mode, boolean frozen, String reason,
                                    Long budget, Integer holdoutBps, String actor, Instant now) {
        try {
            jdbc.update("INSERT INTO evo_tenant_policy (tenant_id, mode, frozen, frozen_reason,"
                            + " daily_token_budget, holdout_bps, updated_by, updated_at)"
                            + " VALUES (?,?,?,?,?,?,?,?)", tenantId, mode, frozen,
                    Db.clip(reason, 255), budget, holdoutBps, actor, ts(now));
        } catch (DuplicateKeyException race) {
            // 并发写入的另一方已建行；本次覆盖由调用方重试即可
        }
    }
}
