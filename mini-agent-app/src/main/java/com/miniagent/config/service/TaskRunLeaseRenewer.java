package com.miniagent.config.service;

import com.miniagent.agent.core.ExecutionControl;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Renews a run's distributed session/quota lease for the lifetime of its worker. */
@Component
public class TaskRunLeaseRenewer {

    private static final Logger log = LoggerFactory.getLogger(TaskRunLeaseRenewer.class);
    private static final long MAX_RENEW_INTERVAL_MILLIS = 30_000L;

    private final TaskRunService taskRunService;
    private final ExecutionControl executionControl;
    private final ScheduledExecutorService scheduler;
    private final Set<LeaseGuard> guards = ConcurrentHashMap.newKeySet();
    private final Object lifecycleLock = new Object();
    private volatile boolean closed;

    @Autowired
    public TaskRunLeaseRenewer(
            TaskRunService taskRunService,
            ExecutionControl executionControl) {
        this(taskRunService, executionControl, newScheduler());
    }

    TaskRunLeaseRenewer(
            TaskRunService taskRunService,
            ExecutionControl executionControl,
            ScheduledExecutorService scheduler) {
        this.taskRunService = taskRunService;
        this.executionControl = executionControl;
        this.scheduler = scheduler;
    }

    /**
     * Starts renewing the lock until the returned guard is closed. Keep the
     * guard in a try-with-resources block around the complete worker window.
     */
    public Guard start(TaskRunService.RunHandle run) {
        if (run == null) throw new IllegalArgumentException("run is required");
        LeaseGuard guard = new LeaseGuard(run);
        boolean rejected;
        synchronized (lifecycleLock) {
            rejected = closed;
            if (!rejected) {
                guards.add(guard);
                // Validate ownership immediately, before any model/tool work.
                renew(guard);
                if (guard.closed.get()) return guard;
                try {
                    long intervalMillis = renewalIntervalMillis(taskRunService.runLockTtlMillis());
                    ScheduledFuture<?> future = scheduler.scheduleWithFixedDelay(
                            () -> renew(guard), intervalMillis, intervalMillis,
                            TimeUnit.MILLISECONDS);
                    guard.setFuture(future);
                } catch (RuntimeException e) {
                    fail(guard, "run lease renewal could not be scheduled", e);
                }
            }
        }
        if (rejected) {
            guard.close();
            cancelRun(run, "run lease renewer is shutting down", null);
        }
        return guard;
    }

    static long renewalIntervalMillis(long ttlMillis) {
        long third = Math.max(1L, ttlMillis) / 3L;
        return Math.max(1L, Math.min(MAX_RENEW_INTERVAL_MILLIS, third));
    }

    private void renew(LeaseGuard guard) {
        synchronized (guard) {
            if (guard.closed.get()) return;
            try {
                if (!taskRunService.renewSessionLock(
                        guard.run.sessionId(), guard.run.fencingToken())) {
                    fail(guard, "run lease ownership was lost", null);
                }
            } catch (RuntimeException e) {
                fail(guard, "run lease renewal failed", e);
            }
        }
    }

    private void fail(LeaseGuard guard, String reason, RuntimeException cause) {
        if (!guard.stop()) return;
        if (cause == null) {
            log.warn("Stopping run after lease renewal failure: runId={} session={} reason={}",
                    guard.run.runId(), guard.run.sessionId(), reason);
        } else {
            log.warn("Stopping run after lease renewal failure: runId={} session={} reason={}",
                    guard.run.runId(), guard.run.sessionId(), reason, cause);
        }
        cancelRun(guard.run, reason, cause);
    }

    private void cancelRun(
            TaskRunService.RunHandle run, String reason, RuntimeException renewalFailure) {
        try {
            // ExecutionControl sets its local cancellation flag before writing
            // the cross-replica signal, so a Redis error still stops this worker.
            executionControl.cancel(run.sessionId(), run.fencingToken());
        } catch (RuntimeException cancelFailure) {
            if (renewalFailure != null && renewalFailure != cancelFailure) {
                cancelFailure.addSuppressed(renewalFailure);
            }
            log.error("Failed to propagate cancellation after lease loss: runId={} session={} reason={}",
                    run.runId(), run.sessionId(), reason, cancelFailure);
        }
    }

    @PreDestroy
    public void shutdown() {
        LeaseGuard[] active;
        synchronized (lifecycleLock) {
            if (closed) return;
            closed = true;
            active = guards.toArray(LeaseGuard[]::new);
        }
        for (LeaseGuard guard : active) {
            synchronized (guard) {
                if (guard.stop()) {
                    cancelRun(guard.run, "run lease renewer is shutting down", null);
                }
            }
        }
        scheduler.shutdownNow();
    }

    private static ScheduledExecutorService newScheduler() {
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "task-run-lease-renewer");
            thread.setDaemon(true);
            return thread;
        };
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(2, factory);
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }

    /** A worker-scoped handle whose close method cannot throw checked exceptions. */
    public interface Guard extends AutoCloseable {
        @Override
        void close();
    }

    private final class LeaseGuard implements Guard {
        private final TaskRunService.RunHandle run;
        private final AtomicBoolean closed = new AtomicBoolean();
        private volatile ScheduledFuture<?> future;

        private LeaseGuard(TaskRunService.RunHandle run) {
            this.run = run;
        }

        private synchronized void setFuture(ScheduledFuture<?> scheduledFuture) {
            future = scheduledFuture;
            if (closed.get()) scheduledFuture.cancel(false);
        }

        private synchronized boolean stop() {
            if (!closed.compareAndSet(false, true)) return false;
            ScheduledFuture<?> scheduledFuture = future;
            if (scheduledFuture != null) scheduledFuture.cancel(false);
            guards.remove(this);
            return true;
        }

        @Override
        public synchronized void close() {
            stop();
        }
    }
}
