package com.miniagent.application;

import org.springframework.context.annotation.DependsOn;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Atomic durable queue. Every completion and renewal is fenced by the claim token. */
@Repository
@DependsOn("postJpaSchemaMigrationBarrier")
public class GoalQueue {
    public enum State { QUEUED, CLAIMED, WAITING_HUMAN, COMPLETED, CANCELLED, SUPERSEDED }
    public record Goal(String id, long userId, String tenantId, String sessionId, long taskId,
                       String executionId, String roundId, String objective, String workspaceRoot,
                       State state, String reason, long nextWakeup, long deadline, int attempts,
                       int noProgress, String progressHash, String leaseToken, long leaseUntil, long version) { }
    private final JdbcTemplate jdbc;
    public GoalQueue(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    private static final org.springframework.jdbc.core.RowMapper<Goal> ROW = (r, n) -> new Goal(
            r.getString("goal_id"), r.getLong("user_id"), r.getString("tenant_id"), r.getString("session_id"),
            r.getLong("task_id"), r.getString("execution_id"), r.getString("recovery_round_id"),
            r.getString("objective"), r.getString("workspace_root"), State.valueOf(r.getString("goal_state")),
            r.getString("wakeup_reason"), r.getLong("next_wakeup_ms"), r.getLong("deadline_ms"),
            r.getInt("attempts"), r.getInt("no_progress"), r.getString("progress_hash"),
            r.getString("lease_token"), r.getLong("lease_until_ms"), r.getLong("row_version"));

    public Optional<Goal> get(String id) { return jdbc.query("SELECT * FROM agent_pending_goals WHERE goal_id=?", ROW, id).stream().findFirst(); }
    public List<Goal> forSession(long owner, String session) {
        return jdbc.query("SELECT * FROM agent_pending_goals WHERE user_id=? AND session_id=? ORDER BY updated_ms DESC LIMIT 10", ROW, owner, session);
    }
    /** Called after a user-owned run. A new explicit round can rearm a suspended goal. */
    public void register(Goal goal, long now) {
        Optional<Goal> existing = get(goal.id());
        if (existing.isEmpty()) {
            try {
                jdbc.update("""
                    INSERT INTO agent_pending_goals (goal_id,user_id,tenant_id,session_id,task_id,execution_id,
                      recovery_round_id,objective,workspace_root,goal_state,wakeup_reason,next_wakeup_ms,
                      deadline_ms,attempts,no_progress,progress_hash,lease_token,lease_until_ms,row_version,updated_ms)
                      VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,0,0,?,NULL,0,0,?)
                    """, goal.id(), goal.userId(), goal.tenantId(), goal.sessionId(), goal.taskId(), goal.executionId(),
                        goal.roundId(), goal.objective(), goal.workspaceRoot(), goal.state().name(), goal.reason(),
                        goal.nextWakeup(), goal.deadline(), goal.progressHash(), now);
                return;
            } catch (DuplicateKeyException raced) { existing = get(goal.id()); }
        }
        Goal old = existing.orElseThrow();
        if (old.userId() != goal.userId() || !old.tenantId().equals(goal.tenantId()))
            throw new SecurityException("Goal owner changed");
        if (old.roundId().equals(goal.roundId())) return; // a repeated terminal callback must not replenish budgets
        int changed = jdbc.update("""
            UPDATE agent_pending_goals SET recovery_round_id=?,objective=?,workspace_root=?,goal_state=?,
              wakeup_reason=?,next_wakeup_ms=?,deadline_ms=?,attempts=0,no_progress=0,progress_hash=?,
              lease_token=NULL,lease_until_ms=0,row_version=row_version+1,updated_ms=?
            WHERE goal_id=? AND row_version=?
            """, goal.roundId(), goal.objective(), goal.workspaceRoot(), goal.state().name(), goal.reason(),
                goal.nextWakeup(), goal.deadline(), goal.progressHash(), now, goal.id(), old.version());
        if (changed != 1) throw new IllegalStateException("Goal registration conflict");
    }
    public List<Goal> due(long now, int limit) {
        return jdbc.query("""
            SELECT * FROM agent_pending_goals
            WHERE (goal_state='QUEUED' AND next_wakeup_ms<=?) OR (goal_state='CLAIMED' AND lease_until_ms<=?)
            ORDER BY next_wakeup_ms,goal_id LIMIT ?
            """, ROW, now, now, Math.max(1, Math.min(32, limit)));
    }
    public Optional<Goal> claim(Goal expected, long now, long leaseMillis, int maximumAttempts) {
        String token = UUID.randomUUID().toString();
        if (expected.deadline() <= now || expected.attempts() >= maximumAttempts) {
            jdbc.update("UPDATE agent_pending_goals SET goal_state='WAITING_HUMAN',wakeup_reason='AUTONOMY_WINDOW_EXHAUSTED',row_version=row_version+1,updated_ms=? WHERE goal_id=? AND row_version=?",
                    now, expected.id(), expected.version());
            return Optional.empty();
        }
        int changed = jdbc.update("""
            UPDATE agent_pending_goals SET goal_state='CLAIMED',lease_token=?,lease_until_ms=?,
              attempts=attempts+1,row_version=row_version+1,updated_ms=?
            WHERE goal_id=? AND row_version=? AND deadline_ms>? AND
              ((goal_state='QUEUED' AND next_wakeup_ms<=?) OR (goal_state='CLAIMED' AND lease_until_ms<=?))
            """, token, now + leaseMillis, now, expected.id(), expected.version(), now, now, now);
        return changed == 1 ? get(expected.id()) : Optional.empty();
    }
    public boolean renew(Goal claim, long now, long leaseMillis) {
        return jdbc.update("UPDATE agent_pending_goals SET lease_until_ms=?,updated_ms=? WHERE goal_id=? AND goal_state='CLAIMED' AND lease_token=? AND lease_until_ms>? AND deadline_ms>?",
                now + leaseMillis, now, claim.id(), claim.leaseToken(), now, now) == 1;
    }
    public boolean finish(Goal claim, State state, String reason, String hash, int noProgress, long nextWake, long now) {
        return jdbc.update("""
            UPDATE agent_pending_goals SET goal_state=?,wakeup_reason=?,progress_hash=?,no_progress=?,
              next_wakeup_ms=?,lease_token=NULL,lease_until_ms=0,row_version=row_version+1,updated_ms=?
            WHERE goal_id=? AND goal_state='CLAIMED' AND lease_token=?
            """, state.name(), reason, hash, noProgress, nextWake, now, claim.id(), claim.leaseToken()) == 1;
    }
    public void suspendForUser(long owner, String session, long now) {
        jdbc.update("UPDATE agent_pending_goals SET goal_state='SUPERSEDED',wakeup_reason='USER_REQUEST',lease_token=NULL,lease_until_ms=0,row_version=row_version+1,updated_ms=? WHERE user_id=? AND session_id=? AND goal_state IN ('QUEUED','CLAIMED','WAITING_HUMAN')", now, owner, session);
    }
    public void cancel(long owner, String session, long now) {
        jdbc.update("UPDATE agent_pending_goals SET goal_state='CANCELLED',wakeup_reason='USER_CANCELLED',lease_token=NULL,lease_until_ms=0,row_version=row_version+1,updated_ms=? WHERE user_id=? AND session_id=? AND goal_state NOT IN ('COMPLETED','CANCELLED','SUPERSEDED')", now, owner, session);
    }
}
