package com.miniagent.config.service;

import com.miniagent.agent.planner.SessionLock;
import com.miniagent.config.entity.AgentTaskRun;
import com.miniagent.config.repository.AgentTaskRunRepository;
import com.miniagent.replica.RedisTaskConcurrency;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.time.Duration;

@Service
public class TaskRunService implements SessionLock {

    private static final Logger log = LoggerFactory.getLogger(TaskRunService.class);

    @Autowired
    private AgentTaskRunRepository repository;
    @Autowired(required = false)
    private RedisTaskConcurrency taskConcurrency;
    @Value("${agent.concurrency.max-tasks-per-user:2}")
    private int maxPerUserConfig;
    private int maxPerUser = 2;
    @Value("${agent.replica.run-lock-ttl-seconds:7200}")
    private long runLockTtlSeconds = 7200L;
    /** 仅 local 模式：本机用户正在运行的任务数 */
    private final ConcurrentHashMap<Long, AtomicInteger> localRunningCount = new ConcurrentHashMap<>();
    /** 仅 local 模式：会话 → fencing token。 */
    private final ConcurrentHashMap<String, String> localSessionLocks = new ConcurrentHashMap<>();

    public record RunHandle(
            Long runId,
            Long userId,
            String sessionId,
            String fencingToken) {
    }

    public record StartResult(RunHandle handle, String error) {
        public boolean accepted() {
            return handle != null;
        }

        static StartResult accepted(RunHandle handle) {
            return new StartResult(handle, null);
        }

        static StartResult rejected(String error) {
            return new StartResult(null, error);
        }
    }

    @PostConstruct
    private void initMaxPerUser() {
        this.maxPerUser = Math.max(1, maxPerUserConfig);
    }

    @PostConstruct
    public void recoverOrphanedRuns() {
        List<AgentTaskRun> running = repository.findByStatus(AgentTaskRun.Status.RUNNING);
        if (running.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        int recovered = 0;
        for (AgentTaskRun run : running) {
            if (taskConcurrency != null
                    && taskConcurrency.isSessionLocked(run.getSessionId())) {
                continue;
            }
            recovered += repository.finishIfRunning(run.getId(), AgentTaskRun.Status.RUNNING,
                    AgentTaskRun.Status.INTERRUPTED, now,
                    "Process restarted; previous run interrupted");
        }
        if (recovered > 0) {
            log.warn("Recovered {} orphaned RUNNING task(s) as INTERRUPTED", recovered);
        }
    }

    public StartResult tryStart(Long userId, String sessionId) {
        if (userId == null) {
            return StartResult.rejected("Not authenticated");
        }
        if (sessionId != null && isRunning(sessionId)) {
            return StartResult.rejected("该会话已有任务在运行");
        }
        String fencingToken = UUID.randomUUID().toString().replace("-", "");

        if (taskConcurrency != null) {
            RunHandle admission = new RunHandle(null, userId, sessionId, fencingToken);
            try {
                if (!taskConcurrency.tryLockSession(sessionId, fencingToken)) {
                    return StartResult.rejected("该会话已有任务在运行");
                }
                if (!taskConcurrency.tryOccupyUserQuota(
                        userId, sessionId, fencingToken, maxPerUser)) {
                    releaseRedisResources(admission);
                    return StartResult.rejected(
                            "并发任务过多（每用户最多 " + maxPerUser + " 个），请等待当前任务完成");
                }
                interruptPreviousRun(sessionId, "Distributed run lease expired");
                AgentTaskRun run = saveRunning(userId, sessionId);
                return StartResult.accepted(new RunHandle(
                        run.getId(), userId, sessionId, fencingToken));
            } catch (Exception e) {
                // A Redis command can time out after its write succeeded. Clean
                // both token-owned resources even when acquisition was unconfirmed.
                releaseRedisResources(admission);
                log.warn("Task admission failed: user={} session={}", userId, sessionId, e);
                return StartResult.rejected("无法启动任务: " + e.getMessage());
            }
        }

        if (sessionId != null
                && localSessionLocks.putIfAbsent(sessionId, fencingToken) != null) {
            return StartResult.rejected("该会话已有任务在运行");
        }

        AtomicInteger counter = localRunningCount.computeIfAbsent(userId, k -> new AtomicInteger(0));
        while (true) {
            int cur = counter.get();
            if (cur >= maxPerUser) {
                if (sessionId != null) {
                    localSessionLocks.remove(sessionId, fencingToken);
                }
                return StartResult.rejected(
                        "并发任务过多（每用户最多 " + maxPerUser + " 个），请等待当前任务完成");
            }
            if (counter.compareAndSet(cur, cur + 1)) {
                break;
            }
        }
        try {
            // The local token is the live-worker authority. A terminal DB write
            // may have failed after its owner released the token; repair that
            // stale row only after this admission owns both lock and quota.
            if (sessionId != null) {
                interruptPreviousRun(sessionId,
                        "Local run ownership lost; previous worker ended without a terminal state");
            }
            AgentTaskRun run = saveRunning(userId, sessionId);
            return StartResult.accepted(new RunHandle(
                    run.getId(), userId, sessionId, fencingToken));
        } catch (Exception e) {
            counter.decrementAndGet();
            if (sessionId != null) {
                localSessionLocks.remove(sessionId, fencingToken);
            }
            return StartResult.rejected("无法启动任务: " + e.getMessage());
        }
    }

    /**
     * Planner 长任务外环续期会话锁。
     * @return false 表示锁已丢（另一实例可能接手），调用方应中止
     */
    public boolean renewSessionLock(String sessionId, String fencingToken) {
        if (sessionId == null) {
            return true;
        }
        if (fencingToken == null || fencingToken.isBlank()) {
            return false;
        }
        if (taskConcurrency != null) {
            return taskConcurrency.renewSessionLock(sessionId, fencingToken);
        }
        return fencingToken.equals(localSessionLocks.get(sessionId));
    }

    private AgentTaskRun saveRunning(Long userId, String sessionId) {
        AgentTaskRun run = new AgentTaskRun();
        run.setUserId(userId);
        run.setSessionId(sessionId);
        run.setStatus(AgentTaskRun.Status.RUNNING);
        return repository.save(run);
    }

    private void interruptPreviousRun(String sessionId, String reason) {
        repository.findFirstBySessionIdAndStatusOrderByStartedAtDesc(
                        sessionId, AgentTaskRun.Status.RUNNING)
                .ifPresent(run -> {
                    // Its owner may have committed completion after our query.
                    // Compare the persisted status rather than saving a stale entity.
                    repository.finishIfRunning(run.getId(), AgentTaskRun.Status.RUNNING,
                            AgentTaskRun.Status.INTERRUPTED, LocalDateTime.now(), reason);
                });
    }

    @Transactional
    public void markCompleted(RunHandle run) {
        finish(run, AgentTaskRun.Status.COMPLETED, null);
    }

    @Transactional
    public void markFailed(RunHandle run, String error) {
        finish(run, AgentTaskRun.Status.FAILED, error);
    }

    @Transactional
    public void markCancelled(RunHandle run, String reason) {
        finish(run, AgentTaskRun.Status.CANCELLED, reason);
    }

    /** This worker has yielded to a human; a later turn may resume the goal. */
    @Transactional
    public void markWaiting(RunHandle run, String reason) {
        finish(run, AgentTaskRun.Status.WAITING, reason);
    }

    private void finish(RunHandle run, AgentTaskRun.Status status, String error) {
        if (run == null) {
            return;
        }
        try {
            String detail = error == null || error.length() <= 900
                    ? error : error.substring(0, 900);
            int updated = repository.finishIfRunning(
                    run.runId(),
                    AgentTaskRun.Status.RUNNING,
                    status,
                    LocalDateTime.now(),
                    detail);
            if (updated == 0) {
                log.warn(
                        "Ignored stale terminal transition runId={} session={} status={}",
                        run.runId(), run.sessionId(), status);
            }
        } finally {
            releaseResources(run);
        }
    }

    private void releaseResources(RunHandle run) {
        if (taskConcurrency != null) {
            releaseRedisResources(run);
            return;
        }
        boolean owned = run.sessionId() == null
                || localSessionLocks.remove(run.sessionId(), run.fencingToken());
        if (owned && run.userId() != null) {
            AtomicInteger c = localRunningCount.get(run.userId());
            if (c != null) {
                c.updateAndGet(v -> Math.max(0, v - 1));
            }
        }
    }

    /** Redis expiry is the final fallback; cleanup must not roll back the DB terminal state. */
    private void releaseRedisResources(RunHandle run) {
        if (run.sessionId() != null) {
            try {
                taskConcurrency.unlockSession(run.sessionId(), run.fencingToken());
            } catch (RuntimeException e) {
                log.warn("Failed to release task session lock; lease will expire: runId={} session={}",
                        run.runId(), run.sessionId(), e);
            }
        }
        if (run.userId() != null) {
            try {
                taskConcurrency.releaseUserQuota(run.userId(), run.sessionId(), run.fencingToken());
            } catch (RuntimeException e) {
                log.warn("Failed to release task user quota; lease will expire: runId={} session={} user={}",
                        run.runId(), run.sessionId(), run.userId(), e);
            }
        }
    }

    public boolean isRunning(String sessionId) {
        if (taskConcurrency != null) {
            return taskConcurrency.isSessionLocked(sessionId);
        }
        return sessionId != null && localSessionLocks.containsKey(sessionId);
    }

    /**
     * Returns the fencing token of the run currently owning a session.
     *
     * <p>Cancellation and other execution signals must be scoped to this
     * token.  In Redis mode the durable lock is authoritative across replicas;
     * in local mode the compare-and-remove map is authoritative.</p>
     */
    public Optional<String> activeRunToken(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        if (taskConcurrency != null) {
            return taskConcurrency.currentSessionToken(sessionId);
        }
        return Optional.ofNullable(localSessionLocks.get(sessionId))
                .filter(token -> !token.isBlank());
    }

    /** Lifetime used by the run-lock renewer in Redis and local configurations. */
    public long runLockTtlMillis() {
        if (taskConcurrency != null) return taskConcurrency.runLockTtlMillis();
        return Duration.ofSeconds(Math.max(1L, runLockTtlSeconds)).toMillis();
    }
}
