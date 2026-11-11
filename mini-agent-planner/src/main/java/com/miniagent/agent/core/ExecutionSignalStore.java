package com.miniagent.agent.core;

/** Cross-replica cancellation, pause and heartbeat control plane. */
public interface ExecutionSignalStore {
    void start(String sessionId, long deadlineEpochMillis, long ttlMillis);
    /** Run-scoped identity: initializing a worker must retain controls sent after admission. */
    default void startPreservingControls(String signalId, long deadlineEpochMillis, long ttlMillis) {
        throw new UnsupportedOperationException("Run-scoped execution signals require atomic initialization");
    }
    void cancel(String sessionId, long ttlMillis);
    boolean isCancelled(String sessionId);
    default void pause(String sessionId, long ttlMillis) {}
    default void resume(String sessionId, long ttlMillis) {}
    default boolean isPaused(String sessionId) { return false; }
    default void markPausedAtCheckpoint(String sessionId, boolean paused, long ttlMillis) {}
    default boolean isPausedAtCheckpoint(String sessionId) { return false; }
    default void markHumanWaitpoint(String sessionId, boolean waiting, long ttlMillis) {}
    default boolean isAtHumanWaitpoint(String sessionId) { return false; }
    void heartbeat(String sessionId, long epochMillis, long ttlMillis);
    void finish(String sessionId);
}
