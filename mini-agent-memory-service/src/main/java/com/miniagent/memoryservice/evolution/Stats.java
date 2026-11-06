package com.miniagent.memoryservice.evolution;

import com.miniagent.memory.contract.Hashes;

import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.SplittableRandom;
import java.util.regex.Pattern;

/** 分桶、贝叶斯判定、失败指纹：纯函数，无状态。 */
public final class Stats {
    public static final int BUCKETS = 10_000;
    private static final int DRAWS = 40_000;
    private static final long SEED = 20261102L;
    private static final int FINGERPRINT_HEX = 16;
    private static final int REASON_CHARS = 200;
    private static final Pattern VOLATILE = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}|[0-9a-f]{12,}|\\d+");
    private static final Pattern PATHS = Pattern.compile("([a-z]:)?[/\\\\][^\\s'\"]+");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    private Stats() {
    }

    /** {@code SHA-256(salt:key)} 的前 8 字节取模，结果在 [0, 10000)。同一任务同一实验永远同一桶。 */
    public static int bucket(String salt, String key) {
        byte[] digest = Hashes.sha256(salt + ":" + key);
        long value = ByteBuffer.wrap(digest, 0, Long.BYTES).getLong() & Long.MAX_VALUE;
        return (int) (value % BUCKETS);
    }

    /**
     * Beta(1,1) 先验下 {@code P(p_b > p_a - margin)}。
     * 蒙特卡洛 + 固定种子：同样的计数永远得到同样的结论，决策可复现。
     */
    public static double probabilityBetter(long successA, long trialsA, long successB,
                                           long trialsB, double margin) {
        SplittableRandom random = new SplittableRandom(SEED);
        int hits = 0;
        for (int i = 0; i < DRAWS; i++) {
            double a = beta(1 + successA, 1 + trialsA - successA, random);
            double b = beta(1 + successB, 1 + trialsB - successB, random);
            if (b > a - margin) {
                hits++;
            }
        }
        return (double) hits / DRAWS;
    }

    /** 错误码 + 去掉数字 / ID / 路径后的失败原因，取 16 位十六进制。同一类失败落同一指纹。 */
    public static String fingerprint(String errorCode, String reason) {
        String normalized = reason == null ? "" : reason.toLowerCase(Locale.ROOT);
        normalized = PATHS.matcher(normalized).replaceAll("<path>");
        normalized = VOLATILE.matcher(normalized).replaceAll("#");
        normalized = SPACES.matcher(normalized).replaceAll(" ").trim();
        if (normalized.length() > REASON_CHARS) {
            normalized = normalized.substring(0, REASON_CHARS);
        }
        String code = errorCode == null ? "" : errorCode;
        return Hashes.sha256Hex(code + "|" + normalized).substring(0, FINGERPRINT_HEX);
    }

    private static double beta(double alpha, double beta, SplittableRandom random) {
        double x = gamma(alpha, random);
        double y = gamma(beta, random);
        return x / (x + y);
    }

    /** Marsaglia–Tsang，要求 shape ≥ 1（Beta(1+s, 1+f) 恒满足）。 */
    private static double gamma(double shape, SplittableRandom random) {
        double d = shape - 1.0 / 3.0;
        double c = 1.0 / Math.sqrt(9.0 * d);
        while (true) {
            double x;
            double v;
            do {
                x = gaussian(random);
                v = 1.0 + c * x;
            } while (v <= 0);
            v = v * v * v;
            double u = random.nextDouble();
            if (u < 1 - 0.0331 * x * x * x * x) {
                return d * v;
            }
            if (Math.log(u) < 0.5 * x * x + d * (1 - v + Math.log(v))) {
                return d * v;
            }
        }
    }

    private static double gaussian(SplittableRandom random) {
        double u1 = random.nextDouble();
        double u2 = random.nextDouble();
        return Math.sqrt(-2.0 * Math.log(1.0 - u1)) * Math.cos(2.0 * Math.PI * u2);
    }
}
