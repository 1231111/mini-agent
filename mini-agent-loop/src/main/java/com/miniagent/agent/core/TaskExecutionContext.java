package com.miniagent.agent.core;

/** Task identity captured for one execution and propagated by {@link RunScope}. */
public final class TaskExecutionContext {
    private static final ThreadLocal<Long> CURRENT = ThreadLocal.withInitial(() -> 0L);

    private TaskExecutionContext() {}

    public static Scope bind(long taskId) {
        long previous = currentTaskId();
        set(taskId);
        return new Scope(previous);
    }

    public static final class Scope implements AutoCloseable {
        private final long previous;
        private boolean closed;

        private Scope(long previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                set(previous);
            }
        }
    }

    public static long currentTaskId() {
        Long taskId = CURRENT.get();
        return taskId == null ? 0L : taskId;
    }

    public static void set(long taskId) {
        CURRENT.set(Math.max(0L, taskId));
    }

    public static void clear() {
        CURRENT.remove();
    }
}
