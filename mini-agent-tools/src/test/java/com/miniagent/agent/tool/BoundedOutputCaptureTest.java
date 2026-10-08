package com.miniagent.agent.tool;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 子进程输出有界捕获的回归测试。
 *
 * <p>背景：{@code exec_command} 此前用 {@code ByteArrayOutputStream} 无上限读子进程输出，
 * 截断发生在读完之后。一条 {@code yes}、一个刷屏构建就能把整个 JVM 读成
 * {@code OutOfMemoryError} —— 失败的是应用进程，不是这一次调用。</p>
 */
class BoundedOutputCaptureTest {

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void contentBelowLimitIsCapturedFully() {
        BoundedOutputCapture cap = new BoundedOutputCapture(1024, 8192);
        cap.write(bytes("hello world"), 11);

        assertEquals("hello world", cap.asString(StandardCharsets.UTF_8));
        assertFalse(cap.truncated(), "未超上限不该标记截断");
        assertFalse(cap.overflowed(), "未超硬上限不该标记失控");
        assertEquals(11, cap.totalBytes());
    }

    @Test
    void contentAboveCaptureLimitIsTruncatedButStillCounted() {
        BoundedOutputCapture cap = new BoundedOutputCapture(64, 4096);
        for (int i = 0; i < 20; i++) {
            cap.write(bytes("0123456789"), 10); // 共 200 字节，上限 64
        }

        String captured = cap.asString(StandardCharsets.UTF_8);
        assertEquals(64, captured.length(), "保留长度必须被上限截住");
        assertTrue(cap.truncated(), "超过捕获上限要标记截断（调用方据此告知模型）");
        assertFalse(cap.overflowed(), "200 字节远未到硬上限");
        assertEquals(200, cap.totalBytes(), "即使不保留内容也要统计总字节，用于如实汇报");
    }

    @Test
    void outputAboveHardLimitIsFlaggedAsOverflowing() {
        BoundedOutputCapture cap = new BoundedOutputCapture(64, 128);
        for (int i = 0; i < 30; i++) {
            cap.write(bytes("0123456789"), 10); // 共 300 字节 > 硬上限 128
        }
        assertTrue(cap.overflowed(), "超过硬上限必须能被判定为失控输出（调用方据此强杀进程树）");
        assertTrue(cap.truncated());
    }

    @Test
    void singleOversizedChunkIsPartiallyCaptured() {
        BoundedOutputCapture cap = new BoundedOutputCapture(16, 1024);
        cap.write(bytes("0123456789ABCDEFGHIJ"), 20);

        assertEquals("0123456789ABCDEF", cap.asString(StandardCharsets.UTF_8),
                "单块超过剩余空间时只写入能放下的部分");
        assertTrue(cap.truncated());
        assertEquals(20, cap.totalBytes());
    }

    @Test
    void emptyAndNullWritesAreIgnored() {
        BoundedOutputCapture cap = new BoundedOutputCapture(16, 32);
        cap.write(null, 10);
        cap.write(bytes(""), 0);
        assertEquals("", cap.asString(StandardCharsets.UTF_8));
        assertEquals(0, cap.totalBytes());
        assertFalse(cap.truncated());
    }
}
