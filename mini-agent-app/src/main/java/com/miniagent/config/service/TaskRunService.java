package com.miniagent.config.service;

import com.miniagent.config.entity.AgentTaskRun;
import com.miniagent.config.repository.AgentTaskRunRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private final AgentTaskRunRepository repository;
    private final int maxPerUser;
    private final ConcurrentHashMap<Long, AtomicInteger> inFlight = new ConcurrentHashMap<>();

    public TaskRunService(
            AgentTaskRunRepository repository,
            @Value("${agent.concurrency.max-tasks-per-user:2}") int maxPerUser) {
        this.repository = repository;
        this.maxPerUser = Math.max(1, maxPerUser);
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
    public String tryAcquire(Long userId, String sessionId) {
        if (userId == null) return "Not authenticated";
        AtomicInteger counter = inFlight.computeIfAbsent(userId, k -> new AtomicInteger(0));
        while (true) {
            int cur = counter.get();
            if (cur >= maxPerUser) {
                return "并发任务过多（每用户最多 " + maxPerUser + " 个），请等待当前任务完成";
            }
            if (counter.compareAndSet(cur, cur + 1)) break;
        }
        try {
            AgentTaskRun run = new AgentTaskRun();
            run.setUserId(userId);
            run.setSessionId(sessionId);
            run.setStatus(AgentTaskRun.Status.RUNNING);
            repository.save(run);
            return null;
        } catch (Exception e) {
            counter.decrementAndGet();
            return "无法启动任务: " + e.getMessage();
        }
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
            if (userId != null) {
                AtomicInteger c = inFlight.get(userId);
                if (c != null) c.updateAndGet(v -> Math.max(0, v - 1));
            }
        }
    }

    public boolean isRunning(String sessionId) {
        return repository.existsBySessionIdAndStatus(sessionId, AgentTaskRun.Status.RUNNING);
    }
}
