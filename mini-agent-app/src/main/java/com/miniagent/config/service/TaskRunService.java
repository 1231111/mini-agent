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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

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
            run.setStatus(AgentTaskRun.Status.INTERRUPTED);
            run.setFinishedAt(now);
            run.setErrorMessage("Process restarted; previous run interrupted");
            repository.save(run);
            recovered++;
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
            if (!taskConcurrency.tryLockSession(sessionId, fencingToken)) {
                return StartResult.rejected("该会话已有任务在运行");
            }
            if (!taskConcurrency.tryOccupyUserQuota(
                    userId, sessionId, fencingToken, maxPerUser)) {
                taskConcurrency.unlockSession(sessionId, fencingToken);
                return StartResult.rejected(
                        "并发任务过多（每用户最多 " + maxPerUser + " 个），请等待当前任务完成");
            }
            try {
                interruptPreviousRun(sessionId);
                AgentTaskRun run = saveRunning(userId, sessionId);
                return StartResult.accepted(new RunHandle(
                        run.getId(), userId, sessionId, fencingToken));
            } catch (Exception e) {
                try {
                    taskConcurrency.unlockSession(sessionId, fencingToken);
                } finally {
                    taskConcurrency.releaseUserQuota(
                            userId, sessionId, fencingToken);
                }
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

    private void interruptPreviousRun(String sessionId) {
        repository.findFirstBySessionIdAndStatusOrderByStartedAtDesc(
                        sessionId, AgentTaskRun.Status.RUNNING)
                .ifPresent(run -> {
                    run.setStatus(AgentTaskRun.Status.INTERRUPTED);
                    run.setFinishedAt(LocalDateTime.now());
                    run.setErrorMessage("Distributed run lease expired");
                    repository.save(run);
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
            try {
                if (run.sessionId() != null) {
                    taskConcurrency.unlockSession(
                            run.sessionId(), run.fencingToken());
                }
            } finally {
                if (run.userId() != null) {
                    taskConcurrency.releaseUserQuota(
                            run.userId(), run.sessionId(), run.fencingToken());
                }
            }
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

    public boolean isRunning(String sessionId) {
        if (taskConcurrency != null) {
            return taskConcurrency.isSessionLocked(sessionId);
        }
        return repository.existsBySessionIdAndStatus(sessionId, AgentTaskRun.Status.RUNNING);
    }
}
