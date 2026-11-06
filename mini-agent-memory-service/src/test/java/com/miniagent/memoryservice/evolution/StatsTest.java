package com.miniagent.memoryservice.evolution;

import org.junit.jupiter.api.Test;

import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatsTest {

    @Test
    void bucketIsDeterministicAndRoughlyUniform() {
        assertEquals(Stats.bucket("salt", "s1:7"), Stats.bucket("salt", "s1:7"));
        long underHalf = IntStream.range(0, 20_000)
                .filter(i -> Stats.bucket("exp-1", "task-" + i) < Stats.BUCKETS / 2).count();
        assertTrue(underHalf > 9_600 && underHalf < 10_400, "split=" + underHalf);
    }

    @Test
    void posteriorProbabilityMatchesIntuition() {
        assertTrue(Stats.probabilityBetter(30, 100, 60, 100, 0) > 0.999);
        assertTrue(Stats.probabilityBetter(60, 100, 30, 100, 0) < 0.001);
        double even = Stats.probabilityBetter(50, 100, 50, 100, 0);
        assertTrue(Math.abs(even - 0.5) < 0.02, "even=" + even);
        // 非劣效边界：同样的成功率，允许 5 个点的差距时「不差于」的概率显著高于 0.5
        assertTrue(Stats.probabilityBetter(80, 100, 80, 100, 0.05) > 0.75);
        assertEquals(Stats.probabilityBetter(7, 10, 9, 10, 0),
                Stats.probabilityBetter(7, 10, 9, 10, 0), "fixed seed must be reproducible");
    }

    @Test
    void fingerprintIgnoresVolatileDetailsButNotErrorCode() {
        String a = Stats.fingerprint("EXECUTION_FAILED",
                "File /tmp/run-123/out.txt not found after 3 retries (id 9f2c11aa77bb)");
        String b = Stats.fingerprint("EXECUTION_FAILED",
                "file /var/x/y.txt  not found after 5 retries (id 0011223344556677)");
        assertEquals(a, b);
        assertEquals(16, a.length());
        assertNotEquals(a, Stats.fingerprint("NOT_FOUND", "file /tmp/a not found after 3 retries"));
    }
}
