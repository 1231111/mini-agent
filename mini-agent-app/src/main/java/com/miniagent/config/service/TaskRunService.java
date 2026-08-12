package com.miniagent.config.service;

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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class TaskRunService {

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
    /** 仅 local 模式：会话互斥 */
    private final ConcurrentHashMap<String, Boolean> localSessionLocks = new ConcurrentHashMap<>();

    @PostConstruct
    private void initMaxPerUser() {
        this.maxPerUser = Math.max(1, maxPerUserConfig);
    }

    @PostConstruct
    public void recoverOrphanedRuns() {
        List<AgentTaskRun> running = repository.findByStatus(AgentTaskRun.Status.RUNNING);
        if (running.isEmpty()) return;
        LocalDateTime now = LocalDateTime.now();
        for (AgentTaskRun run : running) {
            run.setStatus(AgentTaskRun.Status.INTERRUPTED);
            run.setFinishedAt(now);
            run.setErrorMessage("Process restarted; previous run interrupted");
        }
        repository.saveAll(running);
        log.warn("Recovered {} orphaned RUNNING task(s) as INTERRUPTED", running.size());
    }

    /** @return null if accepted, else error message */
    public String tryStart(Long userId, String sessionId) {
        if (userId == null) return "Not authenticated";
        if (sessionId != null && isRunning(sessionId))
            return "该会话已有任务在运行";

        if (taskConcurrency != null) {
            if (!taskConcurrency.tryOccupyUserQuota(userId, maxPerUser)) {
                return "并发任务过多（每用户最多 " + maxPerUser + " 个），请等待当前任务完成";
            }
            if (!taskConcurrency.tryLockSession(sessionId)) {
                taskConcurrency.releaseUserQuota(userId);
                return "该会话已有任务在运行";
            }
            try {
                saveRunning(userId, sessionId);
                return null;
            } catch (Exception e) {
                taskConcurrency.unlockSession(sessionId);
                taskConcurrency.releaseUserQuota(userId);
                return "无法启动任务: " + e.getMessage();
            }
        }

        if (sessionId != null && localSessionLocks.putIfAbsent(sessionId, Boolean.TRUE) != null)
            return "该会话已有任务在运行";

        AtomicInteger counter = localRunningCount.computeIfAbsent(userId, k -> new AtomicInteger(0));
        while (true) {
            int cur = counter.get();
            if (cur >= maxPerUser) {
                if (sessionId != null) localSessionLocks.remove(sessionId);
                return "并发任务过多（每用户最多 " + maxPerUser + " 个），请等待当前任务完成";
            }
            if (counter.compareAndSet(cur, cur + 1)) break;
        }
        try {
            saveRunning(userId, sessionId);
            return null;
        } catch (Exception e) {
            counter.decrementAndGet();
            if (sessionId != null) localSessionLocks.remove(sessionId);
            return "无法启动任务: " + e.getMessage();
        }
    }

    /**
     * Planner 长任务外环续期会话锁。
     * @return false 表示锁已丢（另一实例可能接手），调用方应中止
     */
    public boolean renewSessionLock(String sessionId) {
        if (sessionId == null) return true;
        if (taskConcurrency != null)
            return taskConcurrency.renewSessionLock(sessionId);
        return localSessionLocks.containsKey(sessionId);
    }

    private void saveRunning(Long userId, String sessionId) {
        AgentTaskRun run = new AgentTaskRun();
        run.setUserId(userId);
        run.setSessionId(sessionId);
        run.setStatus(AgentTaskRun.Status.RUNNING);
        repository.save(run);
    }

    @Transactional
    public void markCompleted(Long userId, String sessionId) {
        finish(userId, sessionId, AgentTaskRun.Status.COMPLETED, null);
    }

    @Transactional
    public void markFailed(Long userId, String sessionId, String error) {
        finish(userId, sessionId, AgentTaskRun.Status.FAILED, error);
    }

    private void finish(Long userId, String sessionId, AgentTaskRun.Status status, String error) {
        try {
            repository.findFirstBySessionIdAndStatusOrderByStartedAtDesc(sessionId, AgentTaskRun.Status.RUNNING)
                    .ifPresent(run -> {
                        run.setStatus(status);
                        run.setFinishedAt(LocalDateTime.now());
                        if (error != null) {
                            run.setErrorMessage(error.length() > 900 ? error.substring(0, 900) : error);
                        }
                        repository.save(run);
                    });
        } finally {
            if (taskConcurrency != null) {
                if (sessionId != null) taskConcurrency.unlockSession(sessionId);
                if (userId != null) taskConcurrency.releaseUserQuota(userId);
            } else {
                if (sessionId != null) localSessionLocks.remove(sessionId);
                if (userId != null) {
                    AtomicInteger c = localRunningCount.get(userId);
                    if (c != null) c.updateAndGet(v -> Math.max(0, v - 1));
                }
            }
        }
    }

    public boolean isRunning(String sessionId) {
        return repository.existsBySessionIdAndStatus(sessionId, AgentTaskRun.Status.RUNNING);
    }
}
