package com.miniagent.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Range 解析口径（RFC 7233 实用子集）：
 * 语法非法一律忽略（200 整段），语法合法但不可满足才 416，多段不支持整段返回。
 */
class ByteRangesTest {

    @Test
    void absentOrForeignHeaderFallsBackToFullBody() {
        assertEquals(ByteRanges.Kind.FULL, ByteRanges.parse(null, 100).kind());
        assertEquals(ByteRanges.Kind.FULL, ByteRanges.parse("", 100).kind());
        assertEquals(ByteRanges.Kind.FULL, ByteRanges.parse("chars=0-1", 100).kind());
    }

    @Test
    void simpleRangeIsClampedAtBothEnds() {
        ByteRanges.Result result = ByteRanges.parse("bytes=10-19", 100);
        assertEquals(ByteRanges.Kind.SLICE, result.kind());
        assertEquals(new ByteRanges.Slice(10, 19), result.slice());

        // end 越界钳到 size-1（RFC 要求），不算不可满足
        assertEquals(new ByteRanges.Slice(95, 99), ByteRanges.parse("bytes=95-1000", 100).slice());
    }

    @Test
    void openEndedAndSuffixFormsWork() {
        assertEquals(new ByteRanges.Slice(90, 99), ByteRanges.parse("bytes=90-", 100).slice());
        assertEquals(new ByteRanges.Slice(90, 99), ByteRanges.parse("bytes=-10", 100).slice());
        // 后缀长于文件：整段
        assertEquals(new ByteRanges.Slice(0, 99), ByteRanges.parse("bytes=-500", 100).slice());
    }

    @Test
    void wellFormedButUnsatisfiableIs416() {
        assertEquals(ByteRanges.Kind.UNSATISFIABLE, ByteRanges.parse("bytes=100-200", 100).kind());
        assertEquals(ByteRanges.Kind.UNSATISFIABLE, ByteRanges.parse("bytes=100-", 100).kind());
    }

    @Test
    void malformedSyntaxIsIgnoredNotRejected() {
        assertEquals(ByteRanges.Kind.FULL, ByteRanges.parse("bytes=abc-def", 100).kind());
        assertEquals(ByteRanges.Kind.FULL, ByteRanges.parse("bytes=20-10", 100).kind());
        assertEquals(ByteRanges.Kind.FULL, ByteRanges.parse("bytes=5", 100).kind());
        assertEquals(ByteRanges.Kind.FULL, ByteRanges.parse("bytes=", 100).kind());
    }

    @Test
    void multiRangeIsNotSupportedAndFallsBackToFullBody() {
        assertEquals(ByteRanges.Kind.FULL, ByteRanges.parse("bytes=0-1,5-6", 100).kind());
    }

    @Test
    void emptyFileTreatsAnyRangeAsFullBody() {
        assertEquals(ByteRanges.Kind.FULL, ByteRanges.parse("bytes=0-", 0).kind());
        assertEquals(ByteRanges.Kind.FULL, ByteRanges.parse("bytes=-10", 0).kind());
    }

    @Test
    void sliceCarriesNoExtraPayloadForOtherKinds() {
        assertNull(ByteRanges.parse(null, 100).slice());
        assertNull(ByteRanges.parse("bytes=200-300", 100).slice());
    }
}
