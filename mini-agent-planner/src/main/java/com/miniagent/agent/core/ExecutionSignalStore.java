package com.miniagent.agent.core;

/** Cross-replica cancellation and heartbeat control plane. */
public interface ExecutionSignalStore {
    void start(String sessionId, long deadlineEpochMillis, long ttlMillis);
    void cancel(String sessionId, long ttlMillis);
    boolean isCancelled(String sessionId);
    void heartbeat(String sessionId, long epochMillis, long ttlMillis);
    void finish(String sessionId);
}
