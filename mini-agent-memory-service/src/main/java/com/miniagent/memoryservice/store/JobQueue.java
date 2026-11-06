package com.miniagent.memoryservice.store;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

import static com.miniagent.memoryservice.store.Db.longOrNull;
import static com.miniagent.memoryservice.store.Db.ts;

/**
 * MySQL 作业表 + {@code FOR UPDATE SKIP LOCKED}：多个 worker 副本并发领取互不阻塞。
 * 租约过期的 RUNNING 作业在下一次领取时被回收；每次领取 attempts+1，超过上限转 DEAD。
 * 回写一律带 lease_token，过期租约的迟到结果不会覆盖新租约。
 */
@Repository
public class JobQueue {
    /** EVALUATE / REVALIDATE 由评估 worker 经 HTTP 领取，其余由记忆服务 worker 角色执行。 */
    public enum Type { DETECT, GENERATE, EVALUATE, REVALIDATE, EXPERIMENT_CHECK, MONITOR_CHECK, SWEEP }

    public static final String PENDING = "PENDING";
    public static final String RUNNING = "RUNNING";
    public static final String DONE = "DONE";
    public static final String DEAD = "DEAD";
    /** 非提案作业（检测、检查、扫描）的优先级；提案作业用触发源优先级 1..6。 */
    public static final int DEFAULT_PRIORITY = 0;
    private static final int LEASE_SCAN = 20;

    public record Job(long id, Type type, String openKey, String tenantId, Long itemId,
                      Long proposalId, String payloadJson, int attempts, String leaseToken,
                      String status) {
    }

    /** 领到的作业与因超出重试上限刚转 DEAD 的作业（调用方据此收尾提案）。 */
    public record Leased(List<Job> jobs, List<Job> dead) {
    }

    private final JdbcTemplate jdbc;

    public JobQueue(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** openKey 相同的未完成作业已存在时返回 false（去重）。 */
    public boolean enqueue(Type type, String openKey, String tenantId, Long itemId,
                           Long proposalId, String payloadJson, Instant runAfter, Instant now) {
        return enqueue(type, openKey, tenantId, itemId, proposalId, payloadJson, DEFAULT_PRIORITY,
                runAfter, now);
    }

    public boolean enqueue(Type type, String openKey, String tenantId, Long itemId,
                           Long proposalId, String payloadJson, int priority, Instant runAfter,
                           Instant now) {
        try {
            jdbc.update("INSERT INTO evo_job (job_type, open_key, tenant_id, item_id, proposal_id,"
                            + " payload_json, status, priority, attempts, run_after, created_at,"
                            + " updated_at) VALUES (?,?,?,?,?,?,?,?,0,?,?,?)", type.name(), openKey,
                    tenantId, itemId, proposalId, payloadJson, PENDING, priority, ts(runAfter),
                    ts(now), ts(now));
            return true;
        } catch (DuplicateKeyException duplicate) {
            return false;
        }
    }

    public Optional<Job> find(long id) {
        return jdbc.query("SELECT * FROM evo_job WHERE id = ?", mapper(), id).stream().findFirst();
    }

    /** 领取后记下实际下发的内容（例如评估案例 id），回写时据此校验。 */
    public void setPayload(Job job, String payloadJson) {
        jdbc.update("UPDATE evo_job SET payload_json = ? WHERE id = ? AND lease_token = ?",
                payloadJson, job.id(), job.leaseToken());
    }

    @Transactional
    public Leased lease(Type type, String owner, int max, Duration lease, int maxAttempts,
                        Instant now) {
        return lease(type, owner, max, lease, maxAttempts, now, job -> true);
    }

    /**
     * @param eligible 额外的领取条件（例如每租户并发评估上限）；不满足的行留给下一次
     */
    @Transactional
    public Leased lease(Type type, String owner, int max, Duration lease, int maxAttempts,
                        Instant now, Predicate<Job> eligible) {
        List<Job> candidates = jdbc.query("SELECT * FROM evo_job WHERE job_type = ?"
                        + " AND ((status = ? AND run_after <= ?) OR (status = ? AND lease_until < ?))"
                        + " ORDER BY priority, run_after, id LIMIT ? FOR UPDATE SKIP LOCKED",
                mapper(), type.name(), PENDING, ts(now), RUNNING, ts(now), LEASE_SCAN);
        List<Job> leased = new ArrayList<>();
        List<Job> dead = new ArrayList<>();
        for (Job job : candidates) {
            if (leased.size() >= max) {
                break;
            }
            if (job.attempts() >= maxAttempts) {
                jdbc.update("UPDATE evo_job SET status = ?, open_key = NULL, lease_token = NULL,"
                        + " lease_until = NULL, last_error = COALESCE(last_error, ?),"
                        + " updated_at = ? WHERE id = ?", DEAD, "max attempts exceeded",
                        ts(now), job.id());
                dead.add(job);
                continue;
            }
            if (!eligible.test(job)) {
                continue;
            }
            String token = UUID.randomUUID().toString();
            jdbc.update("UPDATE evo_job SET status = ?, attempts = attempts + 1, lease_owner = ?,"
                            + " lease_token = ?, lease_until = ?, updated_at = ? WHERE id = ?",
                    RUNNING, Db.clip(owner, 64), token, ts(now.plus(lease)), ts(now), job.id());
            leased.add(new Job(job.id(), job.type(), job.openKey(), job.tenantId(), job.itemId(),
                    job.proposalId(), job.payloadJson(), job.attempts() + 1, token, RUNNING));
        }
        return new Leased(leased, dead);
    }

    public boolean complete(Job job, Instant now) {
        return jdbc.update("UPDATE evo_job SET status = ?, open_key = NULL, lease_token = NULL,"
                        + " lease_until = NULL, last_error = NULL, updated_at = ?"
                        + " WHERE id = ? AND lease_token = ?",
                DONE, ts(now), job.id(), job.leaseToken()) == 1;
    }

    /** 周期性检查作业复用同一行：回到 PENDING、清零重试计数。 */
    public boolean reschedule(Job job, Instant runAfter, Instant now) {
        return jdbc.update("UPDATE evo_job SET status = ?, attempts = 0, run_after = ?,"
                        + " lease_token = NULL, lease_until = NULL, last_error = NULL, updated_at = ?"
                        + " WHERE id = ? AND lease_token = ?",
                PENDING, ts(runAfter), ts(now), job.id(), job.leaseToken()) == 1;
    }

    /** 指数退避重试；达到上限转 DEAD 并返回 true。 */
    public boolean fail(Job job, String error, int maxAttempts, Instant now) {
        boolean dead = job.attempts() >= maxAttempts;
        long backoffSeconds = Math.min(3600L, 1L << Math.min(12, job.attempts()));
        jdbc.update("UPDATE evo_job SET status = ?,"
                        + " open_key = CASE WHEN ? THEN NULL ELSE open_key END, run_after = ?,"
                        + " lease_token = NULL, lease_until = NULL, last_error = ?, updated_at = ?"
                        + " WHERE id = ? AND lease_token = ?",
                dead ? DEAD : PENDING, dead, ts(now.plusSeconds(backoffSeconds)),
                Db.clip(error, 1000), ts(now), job.id(), job.leaseToken());
        return dead;
    }

    public int runningForTenant(Type type, String tenantId, Instant now) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM evo_job WHERE job_type = ?"
                        + " AND status = ? AND tenant_id = ? AND lease_until >= ?", Integer.class,
                type.name(), RUNNING, tenantId, ts(now));
        return n == null ? 0 : n;
    }

    public Map<String, Long> depthByTypeAndStatus() {
        Map<String, Long> depth = new HashMap<>();
        jdbc.query("SELECT job_type, status, COUNT(*) AS n FROM evo_job WHERE status <> ?"
                        + " GROUP BY job_type, status",
                rs -> {
                    depth.put(rs.getString("job_type") + ":" + rs.getString("status"),
                            rs.getLong("n"));
                }, DONE);
        return depth;
    }

    public boolean retryDead(long id, Instant now) {
        return jdbc.update("UPDATE evo_job SET status = ?, attempts = 0, run_after = ?,"
                + " updated_at = ? WHERE id = ? AND status = ?", PENDING, ts(now), ts(now), id,
                DEAD) == 1;
    }

    /** 提案结束或条目删除时撤掉其未完成作业（领取中的作业回写会因 token 不匹配而失效）。 */
    public void cancelForProposal(long proposalId, Instant now) {
        jdbc.update("UPDATE evo_job SET status = ?, open_key = NULL, lease_token = NULL,"
                        + " updated_at = ? WHERE proposal_id = ? AND status IN (?, ?)",
                DONE, ts(now), proposalId, PENDING, RUNNING);
    }

    public int purgeDone(Instant before) {
        return jdbc.update("DELETE FROM evo_job WHERE status = ? AND updated_at < ?", DONE,
                ts(before));
    }

    private static RowMapper<Job> mapper() {
        return (rs, n) -> new Job(rs.getLong("id"), Type.valueOf(rs.getString("job_type")),
                rs.getString("open_key"), rs.getString("tenant_id"), longOrNull(rs, "item_id"),
                longOrNull(rs, "proposal_id"), rs.getString("payload_json"),
                rs.getInt("attempts"), rs.getString("lease_token"), rs.getString("status"));
    }
}
