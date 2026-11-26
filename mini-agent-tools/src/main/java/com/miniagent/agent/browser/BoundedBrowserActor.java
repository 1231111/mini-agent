package com.miniagent.agent.browser;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** One SDK owner thread with bounded dispatch, wait, quarantine, and owner-thread cleanup. */
final class BoundedBrowserActor {
    enum Status { COMPLETED, QUEUE_FULL, UNAVAILABLE, EXPIRED_QUEUED, EXPIRED_RUNNING,
        CANCELLED_QUEUED, CANCELLED_RUNNING }
    record Result<T>(Status status, T value) { }

    private final ThreadPoolExecutor executor;
    private final Object dispatchLock = new Object();
    private final AtomicBoolean quarantined = new AtomicBoolean();
    private final Runnable abortOwnedProcess;
    private final Runnable cleanupOnOwner;
    private volatile Thread owner;
    private volatile long lastActiveNanos = System.nanoTime();

    BoundedBrowserActor(String name, int queueCapacity, Runnable abortOwnedProcess,
                        Runnable cleanupOnOwner) {
        this.abortOwnedProcess = abortOwnedProcess;
        this.cleanupOnOwner = cleanupOnOwner;
        executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), runnable -> {
                    Thread thread = new Thread(() -> {
                        owner = Thread.currentThread();
                        runnable.run();
                    }, name);
                    thread.setDaemon(true);
                    thread.setContextClassLoader(BrowserService.class.getClassLoader());
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    boolean isOwnerThread() { return Thread.currentThread() == owner; }
    boolean isTerminated() { return executor.isTerminated(); }
    boolean isQuarantined() { return quarantined.get(); }
    int queuedTaskCount() { return executor.getQueue().size(); }
    boolean isIdleFor(long idleNanos) {
        return executor.getActiveCount() == 0 && executor.getQueue().isEmpty()
                && System.nanoTime() - lastActiveNanos >= idleNanos;
    }

    <T> Result<T> invoke(Callable<T> operation, long timeoutMillis, Runnable stopInput,
                         Runnable completed) {
        if (isOwnerThread()) {
            try { return new Result<>(Status.COMPLETED, call(operation)); }
            finally { completed.run(); }
        }
        AtomicBoolean completionDone = new AtomicBoolean();
        Runnable completeOnce = () -> { if (completionDone.compareAndSet(false, true)) completed.run(); };
        if (Thread.currentThread().isInterrupted()) {
            completeOnce.run();
            return new Result<>(Status.CANCELLED_QUEUED, null);
        }
        ActorTask<T> task = new ActorTask<>(operation, completeOnce);
        try {
            // Admission and quarantine are atomic: no request can slip behind the cleanup task.
            synchronized (dispatchLock) {
                if (quarantined.get()) {
                    completeOnce.run();
                    return new Result<>(Status.UNAVAILABLE, null);
                }
                executor.execute(task);
            }
        } catch (RejectedExecutionException unavailable) {
            completeOnce.run();
            return new Result<>(quarantined.get() ? Status.UNAVAILABLE : Status.QUEUE_FULL, null);
        }
        try {
            T value = task.get(Math.max(1, timeoutMillis), TimeUnit.MILLISECONDS);
            return new Result<>(Status.COMPLETED, value);
        } catch (TimeoutException expired) {
            stopInput.run();
            if (task.cancelBeforeStart()) return new Result<>(Status.EXPIRED_QUEUED, null);
            quarantine();
            return new Result<>(Status.EXPIRED_RUNNING, null);
        } catch (InterruptedException cancelled) {
            stopInput.run();
            boolean beforeStart = task.cancelBeforeStart();
            if (!beforeStart) quarantine();
            Thread.currentThread().interrupt();
            return new Result<>(beforeStart ? Status.CANCELLED_QUEUED : Status.CANCELLED_RUNNING, null);
        } catch (CancellationException discarded) {
            return new Result<>(Status.UNAVAILABLE, null);
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof CancellationException) return new Result<>(Status.UNAVAILABLE, null);
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException(cause);
        }
    }

    /** Never call Playwright here: only an owned ProcessHandle may be used outside its owner thread. */
    void quarantine() {
        List<Runnable> discarded = new ArrayList<>();
        synchronized (dispatchLock) {
            if (!quarantined.compareAndSet(false, true)) return;
            executor.getQueue().drainTo(discarded);
            // Cleanup must run on the Playwright owner thread, after any in-flight operation exits.
            try {
                executor.execute(() -> {
                    try { cleanupOnOwner.run(); }
                    finally { lastActiveNanos = System.nanoTime(); }
                });
            } catch (RejectedExecutionException noCleanupSlot) {
                // A worker that has already terminated has nothing left to clean up.
                // Keep shutdown idempotent and never turn a caller timeout into a new failure.
            }
            executor.shutdown();
        }
        for (Runnable runnable : discarded) {
            if (runnable instanceof BoundedBrowserActor.ActorTask<?> task) task.cancelBeforeStart();
        }
        // A native-process cleanup error cannot change a timeout into an uncaught tool exception.
        try { abortOwnedProcess.run(); } catch (RuntimeException ignored) { }
    }

    private static <T> T call(Callable<T> operation) {
        try { return operation.call(); }
        catch (RuntimeException runtime) { throw runtime; }
        catch (Exception checked) { throw new IllegalStateException(checked); }
    }

    private final class ActorTask<T> extends FutureTask<T> {
        private final AtomicInteger stage;
        private final Runnable completed;
        ActorTask(Callable<T> operation, Runnable completed) {
            this(operation, completed, new AtomicInteger());
        }
        private ActorTask(Callable<T> operation, Runnable completed, AtomicInteger stage) {
            super(() -> {
                synchronized (dispatchLock) {
                    // A task may already have been taken from the queue when quarantine drains it.
                    // Starting the callable must obey the same admission boundary as execute().
                    if (quarantined.get() || !stage.compareAndSet(0, 1)) {
                        completed.run();
                        throw new CancellationException();
                    }
                }
                try { return operation.call(); }
                finally { stage.set(2); completed.run(); }
            });
            this.stage = stage;
            this.completed = completed;
        }
        boolean cancelBeforeStart() {
            if (!stage.compareAndSet(0, 3)) return false;
            super.cancel(false);
            completed.run();
            executor.remove(this);
            return true;
        }
        @Override public void run() {
            lastActiveNanos = System.nanoTime();
            try { super.run(); }
            finally { lastActiveNanos = System.nanoTime(); }
        }
    }
}
