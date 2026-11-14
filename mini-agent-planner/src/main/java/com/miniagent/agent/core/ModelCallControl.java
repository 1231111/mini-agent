package com.miniagent.agent.core;

import org.springframework.stereotype.Component;

import java.util.concurrent.*;

/** Keeps blocking provider calls inside the current run's cancellation and deadline boundary. */
@Component
public final class ModelCallControl {
    private static final ExecutorService WORKERS = Executors.newVirtualThreadPerTaskExecutor();
    // An SDK may ignore interrupts. Bound those outstanding requests until their workers actually finish.
    private static final Semaphore CAPACITY = new Semaphore(32);
    private static final long POLL_MILLIS = 200L;
    private final ExecutionControl executionControl;

    public ModelCallControl(ExecutionControl executionControl) {
        this.executionControl = executionControl;
    }

    public <T> T call(Callable<T> operation) {
        String session = AgentLoop.getCurrentSession();
        if (session == null || executionControl == null) return invoke(operation);
        checkpoint(session);
        RunScope scope = RunScope.capture();
        boolean acquired = false;
        Future<T> request = null;
        try {
            while (!(acquired = CAPACITY.tryAcquire(POLL_MILLIS, TimeUnit.MILLISECONDS))) {
                checkWaiting(session);
            }
            checkWaiting(session);
            FutureTask<T> pending = new FutureTask<>(() -> {
                try (var ignored = scope.bind()) {
                    checkpoint(session);
                    return operation.call();
                }
            });
            WORKERS.execute(() -> {
                try { pending.run(); }
                finally { CAPACITY.release(); }
            });
            request = pending;
            acquired = false; // The worker owns this permit, including when it ignores cancellation.
            while (true) {
                checkWaiting(session);
                try {
                    T response = request.get(POLL_MILLIS, TimeUnit.MILLISECONDS);
                    checkpoint(session); // Never publish a late response or advance while paused.
                    return response;
                } catch (TimeoutException waiting) {
                    // Cancellation and the run deadline are independent of provider timeouts.
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new StoppedException(ExecutionControl.StopReason.CANCELLED);
        } catch (ExecutionException failed) {
            checkWaiting(session);
            Throwable cause = failed.getCause();
            rethrowIfStopped(cause);
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("Model request failed", cause);
        } finally {
            if (request != null && !request.isDone()) request.cancel(true);
            if (acquired) CAPACITY.release();
        }
    }

    private static <T> T invoke(Callable<T> operation) {
        try {
            return operation.call();
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            rethrowIfStopped(failure);
            throw new IllegalStateException("Model request failed", failure);
        }
    }

    private void checkWaiting(String session) {
        stopIfInterrupted();
        checkDispatchFence();
        stop(executionControl.stopReason(session));
        if (executionControl.isPauseRequested(session)) checkpoint(session);
    }

    public void checkpoint(String session) {
        stopIfInterrupted();
        checkDispatchFence();
        stop(executionControl.checkpoint(session));
        checkDispatchFence();
    }

    /** Provider deltas use the revocation latch owned by the 200ms request monitor.
     * Do not query the planner database for every streamed token. Final responses
     * and all tool dispatches still revalidate the authoritative fence. */
    void checkpointForCallback(String session) {
        stopIfInterrupted();
        ExecutionTurnContext.Scope scope = ExecutionTurnContext.current();
        if (scope != null && scope.isRejected()) throw new StoppedException(ExecutionControl.StopReason.CANCELLED);
        stop(executionControl.checkpoint(session));
        if (scope != null && scope.isRejected()) throw new StoppedException(ExecutionControl.StopReason.CANCELLED);
    }

    private static void checkDispatchFence() {
        ExecutionTurnContext.Scope scope = ExecutionTurnContext.current();
        if (scope == null) return;
        boolean valid;
        try { valid = scope.isValid(); }
        catch (RuntimeException unavailable) {
            // An unavailable authority cannot authorize another provider call or a late callback.
            throw new StoppedException(ExecutionControl.StopReason.CANCELLED);
        }
        if (!valid) throw new StoppedException(ExecutionControl.StopReason.CANCELLED);
    }

    private static void stopIfInterrupted() {
        if (Thread.currentThread().isInterrupted())
            throw new StoppedException(ExecutionControl.StopReason.CANCELLED);
    }

    private static void stop(ExecutionControl.StopReason reason) {
        if (reason != ExecutionControl.StopReason.NONE) throw new StoppedException(reason);
    }

    /** Broad error recovery must not turn a control stop into fallback execution or a retry. */
    public static void rethrowIfStopped(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof StoppedException stopped) throw stopped;
            if (cause instanceof CancellationException cancelled) throw cancelled;
            if (cause instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                throw new StoppedException(ExecutionControl.StopReason.CANCELLED);
            }
            if (cause == cause.getCause()) break;
        }
        stopIfInterrupted();
    }

    public static final class StoppedException extends CancellationException {
        private final ExecutionControl.StopReason reason;

        public StoppedException(ExecutionControl.StopReason reason) {
            super(reason == ExecutionControl.StopReason.DEADLINE_EXCEEDED
                    ? "任务已超过执行 deadline" : "模型调用已停止: " + reason);
            this.reason = reason;
        }

        public ExecutionControl.StopReason reason() { return reason; }
    }
}
