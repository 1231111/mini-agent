package com.miniagent.agent.tool;

import java.util.concurrent.TimeUnit;

/** Monotonic handler deadline that can be carried to a tool's dedicated SDK thread. */
public final class ToolExecutionDeadline {
    private static final ThreadLocal<Deadline> CURRENT = new ThreadLocal<>();

    private ToolExecutionDeadline() { }

    public static Deadline current() { return CURRENT.get(); }

    public static Deadline after(long timeout, TimeUnit unit) {
        if (timeout <= 0) throw new IllegalArgumentException("Tool timeout must be positive");
        return new Deadline(System.nanoTime(), unit.toNanos(timeout));
    }

    /** Nested operations may tighten, but never restart or extend, the enclosing budget. */
    public static Binding bind(Deadline requested) {
        Deadline previous = CURRENT.get();
        Deadline effective = requested;
        if (previous != null && (requested == null || previous.remainingNanos() < requested.remainingNanos()))
            effective = previous;
        if (effective == null) CURRENT.remove();
        else CURRENT.set(effective);
        return () -> {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        };
    }

    public static final class Deadline {
        private final long startedNanos;
        private final long timeoutNanos;

        private Deadline(long startedNanos, long timeoutNanos) {
            this.startedNanos = startedNanos;
            this.timeoutNanos = timeoutNanos;
        }

        private long remainingNanos() {
            return Math.max(0L, timeoutNanos - (System.nanoTime() - startedNanos));
        }

        /** Never pass zero to SDKs where it means an unlimited wait. */
        public long timeoutMillis(long completionReserveMillis) {
            long remaining = TimeUnit.NANOSECONDS.toMillis(remainingNanos());
            long reserve = Math.max(0L, completionReserveMillis);
            if (remaining <= reserve) throw new Expired();
            return remaining - reserve;
        }
    }

    public static final class Expired extends RuntimeException {
        public Expired() { super("工具执行预算已耗尽，未启动新的浏览器观察"); }
    }

    @FunctionalInterface
    public interface Binding extends AutoCloseable {
        @Override void close();
    }
}
