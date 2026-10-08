package com.miniagent.agent.tool;

import java.nio.charset.Charset;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 子进程输出的有界捕获。
 *
 * <p>为什么需要它：{@code exec_command} 此前用 {@code ByteArrayOutputStream} 无上限地读子进程输出，
 * 截断发生在**读完之后**（{@code clampForContext} 才截字符串）。于是一条
 * {@code yes}、或一个刷屏的构建，就能把整个 JVM 读成 {@code OutOfMemoryError} ——
 * 拖垮的是应用进程，不是这一次工具调用。</p>
 *
 * <p>三个阈值：</p>
 * <ul>
 *   <li>捕获上限：超过之后<b>继续读但不再累积</b>（必须继续读，否则子进程会因管道写满而阻塞）；</li>
 *   <li>硬上限：超过即判定"输出失控"，由调用方强杀整棵进程树；</li>
 *   <li>总字节数：即使不保留内容也要统计，用于告诉模型"截断了多少"。</li>
 * </ul>
 *
 * <p>线程安全：只被 drainer 线程写、主线程在 join/超时后读，用 synchronized 保证可见性。</p>
 */
final class BoundedOutputCapture {

    private final int captureLimit;
    private final int hardLimit;
    private final java.io.ByteArrayOutputStream buf;
    private final AtomicLong total = new AtomicLong();
    private final AtomicBoolean truncated = new AtomicBoolean();
    private final AtomicBoolean overflowed = new AtomicBoolean();

    BoundedOutputCapture(int captureLimit, int hardLimit) {
        // 不要在这里加"最小值兜底"：调用方给的是显式契约，被悄悄抬高会让
        // "上限到底是多少"变得不可预测（测试也证明不了边界行为）。
        this.captureLimit = Math.max(1, captureLimit);
        this.hardLimit = Math.max(this.captureLimit, hardLimit);
        this.buf = new java.io.ByteArrayOutputStream(Math.min(this.captureLimit, 64 * 1024));
    }

    void write(byte[] chunk, int length) {
        if (chunk == null || length <= 0) {
            return;
        }
        long after = total.addAndGet(length);
        if (after > hardLimit) {
            overflowed.set(true);
        }
        synchronized (this) {
            int room = captureLimit - buf.size();
            if (room <= 0) {
                truncated.set(true);
                return;
            }
            int toWrite = Math.min(room, length);
            buf.write(chunk, 0, toWrite);
            if (toWrite < length) {
                truncated.set(true);
            }
        }
    }

    synchronized String asString(Charset charset) {
        return new String(buf.toByteArray(), charset == null ? Charset.defaultCharset() : charset);
    }

    long totalBytes() {
        return total.get();
    }

    /** 内容被截断（超出捕获上限）。 */
    boolean truncated() {
        return truncated.get();
    }

    /** 输出失控（超出硬上限）：调用方应当强杀进程。 */
    boolean overflowed() {
        return overflowed.get();
    }
}
