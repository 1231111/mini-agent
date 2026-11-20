package com.miniagent.agent.memory.manager;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/** Bounded in-memory retry backoff for durable cloud-sync queues. */
final class CloudSyncBackoff {
    static final long MAX_DELAY_MILLIS = 15 * 60 * 1000L;
    private static final int MAX_ENTRIES = 10_000;

    private final long baseDelayMillis;
    private final LongSupplier nowMillis;
    private final LinkedHashMap<String, State> states = new LinkedHashMap<>(16, 0.75f, true);

    CloudSyncBackoff(long baseDelayMillis) {
        this(baseDelayMillis, System::currentTimeMillis);
    }

    CloudSyncBackoff(long baseDelayMillis, LongSupplier nowMillis) {
        this.baseDelayMillis = Math.max(1_000L, Math.min(MAX_DELAY_MILLIS, baseDelayMillis));
        this.nowMillis = nowMillis;
    }

    synchronized long retryAfterMillis(String ownerKey) {
        State state = states.get(ownerKey);
        return state == null ? 0 : Math.max(0, state.blockedUntilMillis() - nowMillis.getAsLong());
    }

    /** Atomically reserves one sync attempt and suppresses overlapping scheduled passes. */
    synchronized boolean tryBegin(String ownerKey) {
        long now = nowMillis.getAsLong();
        State previous = states.get(ownerKey);
        if (previous != null && previous.blockedUntilMillis() > now) return false;
        int attempts = previous == null ? 0 : previous.attempts();
        states.put(ownerKey, new State(attempts, now + MAX_DELAY_MILLIS));
        evictOldestIfNeeded();
        return true;
    }

    synchronized long recordFailure(String ownerKey) {
        State previous = states.get(ownerKey);
        int attempts = previous == null ? 1 : Math.min(32, previous.attempts() + 1);
        long delay = exponentialDelay(attempts);
        // Stable per owner and attempt, with up to 20% jitter to spread retry bursts.
        long jitterWindow = Math.max(1, delay / 5);
        long jitter = Math.floorMod(31L * ownerKey.hashCode() + attempts * 17L, jitterWindow);
        delay = Math.max(baseDelayMillis, delay - jitter);
        states.put(ownerKey, new State(attempts, nowMillis.getAsLong() + delay));
        evictOldestIfNeeded();
        return delay;
    }

    synchronized void recordSuccess(String ownerKey) {
        states.remove(ownerKey);
    }

    synchronized void recordSuccess(String ownerKey, long cooldownMillis) {
        if (cooldownMillis <= 0) {
            states.remove(ownerKey);
            return;
        }
        states.put(ownerKey, new State(0, nowMillis.getAsLong() + cooldownMillis));
        evictOldestIfNeeded();
    }

    private long exponentialDelay(int attempts) {
        int shift = Math.min(30, attempts - 1);
        long multiplier = 1L << shift;
        if (baseDelayMillis > MAX_DELAY_MILLIS / multiplier) return MAX_DELAY_MILLIS;
        return Math.min(MAX_DELAY_MILLIS, baseDelayMillis * multiplier);
    }

    private void evictOldestIfNeeded() {
        while (states.size() > MAX_ENTRIES) {
            Map.Entry<String, State> eldest = states.entrySet().iterator().next();
            states.remove(eldest.getKey());
        }
    }

    private record State(int attempts, long blockedUntilMillis) { }
}
