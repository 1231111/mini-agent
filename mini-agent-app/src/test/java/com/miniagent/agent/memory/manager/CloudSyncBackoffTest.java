package com.miniagent.agent.memory.manager;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloudSyncBackoffTest {
    @Test
    void reservesOneAttemptAndHonorsTheConfiguredSuccessPollInterval() {
        AtomicLong now = new AtomicLong(1_000L);
        CloudSyncBackoff backoff = new CloudSyncBackoff(5_000L, now::get);

        assertTrue(backoff.tryBegin("owner"));
        assertFalse(backoff.tryBegin("owner"));
        backoff.recordSuccess("owner", 3_000L);
        now.addAndGet(2_999L);
        assertFalse(backoff.tryBegin("owner"));
        now.incrementAndGet();
        assertTrue(backoff.tryBegin("owner"));
    }

    @Test
    void growsRetryDelayWithOwnerIsolationAndResetsOnSuccess() {
        AtomicLong now = new AtomicLong(1_000L);
        CloudSyncBackoff backoff = new CloudSyncBackoff(10_000L, now::get);

        assertEquals(0, backoff.retryAfterMillis("tenant-a/user-1"));
        assertEquals(10_000L, backoff.recordFailure("tenant-a/user-1"));
        assertEquals(10_000L, backoff.retryAfterMillis("tenant-a/user-1"));
        assertEquals(0, backoff.retryAfterMillis("tenant-a/user-2"));

        now.addAndGet(10_000L);
        long secondDelay = backoff.recordFailure("tenant-a/user-1");
        assertTrue(secondDelay >= 16_000L && secondDelay <= 20_000L);

        backoff.recordSuccess("tenant-a/user-1");
        assertEquals(0, backoff.retryAfterMillis("tenant-a/user-1"));
    }

    @Test
    void capsDelayAndNeverUsesAnUnboundedOwnerStateMap() {
        AtomicLong now = new AtomicLong();
        CloudSyncBackoff backoff = new CloudSyncBackoff(CloudSyncBackoff.MAX_DELAY_MILLIS,
                now::get);
        long delay = backoff.recordFailure("owner");
        for (int i = 0; i < 40; i++) {
            now.addAndGet(delay);
            delay = backoff.recordFailure("owner");
        }
        assertTrue(delay <= CloudSyncBackoff.MAX_DELAY_MILLIS);
        assertTrue(delay >= CloudSyncBackoff.MAX_DELAY_MILLIS * 4 / 5);
    }
}
