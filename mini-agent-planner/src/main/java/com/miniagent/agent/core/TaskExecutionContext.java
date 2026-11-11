package com.miniagent.agent.core;

/** Task identity captured for one execution and propagated by {@link RunScope}. */
public final class TaskExecutionContext {
    private record Identity(long taskId, String runToken) {}
    private static final ThreadLocal<Identity> CURRENT =
            ThreadLocal.withInitial(() -> new Identity(0L, null));

    private TaskExecutionContext() {}

    public static Scope bind(long taskId) {
        Identity previous = CURRENT.get();
        set(taskId);
        return new Scope(previous);
    }

    public static final class Scope implements AutoCloseable {
        private final Identity previous;
        private boolean closed;

        private Scope(Identity previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                CURRENT.set(previous);
            }
        }
    }

    public static long currentTaskId() {
        return CURRENT.get().taskId();
    }

    public static String currentRunToken() { return CURRENT.get().runToken(); }

    public static Scope bindRun(String runToken) {
        Identity previous = CURRENT.get();
        set(previous.taskId(), runToken);
        return new Scope(previous);
    }

    public static void set(long taskId) {
        set(taskId, currentRunToken());
    }

    static void set(long taskId, String runToken) {
        CURRENT.set(new Identity(Math.max(0L, taskId), runToken));
    }

    public static void clear() {
        CURRENT.remove();
    }
}
