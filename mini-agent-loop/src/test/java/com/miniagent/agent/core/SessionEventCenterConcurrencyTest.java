package com.miniagent.agent.core;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * P1-4 并发撕裂回归。
 *
 * <p>think / answer 两个缓冲的 append 与 toString / delete 必须互斥。旧实现是裸
 * {@link StringBuilder}：多线程并发时 {@code ensureCapacityInternal} 会在两条线程里
 * 各自替换 value 数组，后写的那次可能用一个更小的数组覆盖掉更大的；此时另一条线程的
 * {@code toString()} 依据已增长的 count 去读，就抛 StringIndexOutOfBoundsException。
 * 该异常在 sendEvent 里被吞掉，表现为「重连上来的客户端被误当成死连接摘掉」，
 * 轻一些的表现是思考文本丢字符（"given a closing conditions" 变
 * "ivennaraclosingconaitons"）。
 *
 * <p>本测试不经 Spring 容器，直接反射打真实类的两个私有读写点，断言整轮压测里
 * 一次异常都不能有。
 */
class SessionEventCenterConcurrencyTest {

    @Test
    void appendedMessagesAreRejectedOutsideRunningSession() {
        SessionEventCenter center = new SessionEventCenter();

        assertFalse(center.appendUserMessage("missing", "stale"));
        center.start("s1", "hello");
        assertTrue(center.appendUserMessage("s1", "during run"));
        center.complete("s1", "done");
        assertFalse(center.appendUserMessage("s1", "after completion"));
    }

    @Test
    void concurrentAppendAndSnapshotNeverThrows() throws Exception {
        SessionEventCenter.SessionChannel channel = new SessionEventCenter.SessionChannel();

        Method applyEvent = SessionEventCenter.class.getDeclaredMethod(
                "applyEvent", SessionEventCenter.SessionChannel.class, String.class, String.class);
        applyEvent.setAccessible(true);
        Method snapshot = SessionEventCenter.class.getDeclaredMethod(
                "snapshot", SessionEventCenter.SessionChannel.class, StringBuilder.class);
        snapshot.setAccessible(true);

        final int writers = 6;
        final int readers = 4;
        final int iterations = 4_000;
        // 200 字符一块，足以反复触发内部数组扩容，把「扩容 vs 读 count」的竞态压出来
        final String chunk = "abcdefghij".repeat(20);

        ExecutorService pool = Executors.newFixedThreadPool(writers + readers);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(writers + readers);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicLong snapshots = new AtomicLong();

        for (int i = 0; i < writers; i++) {
            final boolean alsoToken = (i == writers - 1);
            pool.execute(() -> {
                try {
                    start.await();
                    for (int n = 0; n < iterations; n++) {
                        applyEvent.invoke(null, channel, "thinking", chunk);
                        if (alsoToken && (n & 7) == 0) {
                            applyEvent.invoke(null, channel, "token", chunk);
                        }
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                } finally {
                    done.countDown();
                }
            });
        }
        for (int i = 0; i < readers; i++) {
            pool.execute(() -> {
                try {
                    start.await();
                    for (int n = 0; n < iterations * 4; n++) {
                        snapshot.invoke(null, channel, channel.think);
                        snapshot.invoke(null, channel, channel.answer);
                        snapshots.incrementAndGet();
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS), "并发压测未在 60s 内结束");
        pool.shutdownNow();

        Throwable t = failure.get();
        assertNull(t, () -> "并发读写 think/answer 抛异常：" + describe(t));
        assertTrue(snapshots.get() > 0, "读端一次都没跑起来");
        // 裁剪上限仍然生效：thinking 缓冲不得超过 THINK_BUFFER_MAX_CHARS(16_000)
        assertTrue(channel.think.length() <= 16_000,
                "think 缓冲超出上限：" + channel.think.length());
    }

    @Test
    void thinkingBufferKeepsOnlyTailWithinCap() throws Exception {
        SessionEventCenter.SessionChannel channel = new SessionEventCenter.SessionChannel();
        Method applyEvent = SessionEventCenter.class.getDeclaredMethod(
                "applyEvent", SessionEventCenter.SessionChannel.class, String.class, String.class);
        applyEvent.setAccessible(true);

        for (int i = 0; i < 200; i++) {
            applyEvent.invoke(null, channel, "thinking", "0123456789");
        }
        // 2000 字符远小于 16_000 上限，内容应完整保留
        assertEquals(2_000, channel.think.length());

        for (int i = 0; i < 4_000; i++) {
            applyEvent.invoke(null, channel, "thinking", "0123456789");
        }
        // 合计 42_000 字符 → 只留尾部 16_000
        assertEquals(16_000, channel.think.length());
        assertTrue(channel.think.toString().endsWith("0123456789"));
    }

    private static String describe(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getClass().getName() + ": " + root.getMessage();
    }
}
