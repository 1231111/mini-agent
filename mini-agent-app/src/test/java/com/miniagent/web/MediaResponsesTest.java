package com.miniagent.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 媒体出口的 Range 行为：音频拖进度条全靠它。
 *
 * <p>挡的回归：改造前端点对 Range 头无感知，一律 200 整段重发，
 * 浏览器播放器拿到 200 就认为服务端不支持拖动，进度条直接锁死。</p>
 */
class MediaResponsesTest {

    private static final String CONTENT_TYPE = "audio/mpeg";

    @TempDir
    Path dir;

    /** 100 字节，第 i 字节值为 i，方便断言切片内容。 */
    private Path mediaFile() throws IOException {
        byte[] bytes = new byte[100];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) i;
        }
        Path file = dir.resolve("a.mp3");
        Files.write(file, bytes);
        return file;
    }

    private static byte[] body(ResponseEntity<?> response) throws IOException {
        Resource resource = (Resource) response.getBody();
        try (InputStream in = resource.getInputStream()) {
            return in.readAllBytes();
        }
    }

    private static ResponseEntity<?> serve(Path file, String range, String ifRange) throws IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (range != null) {
            request.addHeader(HttpHeaders.RANGE, range);
        }
        if (ifRange != null) {
            request.addHeader(HttpHeaders.IF_RANGE, ifRange);
        }
        return MediaResponses.serve(file, "a.mp3", CONTENT_TYPE, request);
    }

    @Test
    void withoutRangeItServesFullBodyAndAdvertisesSeekability() throws IOException {
        ResponseEntity<?> response = serve(mediaFile(), null, null);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("bytes", response.getHeaders().getFirst(HttpHeaders.ACCEPT_RANGES));
        assertEquals(100, response.getHeaders().getContentLength());
        assertEquals(100, body(response).length);
    }

    @Test
    void singleRangeReturns206WithContentRange() throws IOException {
        ResponseEntity<?> response = serve(mediaFile(), "bytes=10-19", null);

        assertEquals(206, response.getStatusCode().value());
        assertEquals("bytes 10-19/100", response.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE));
        assertEquals(10, response.getHeaders().getContentLength());
        byte[] expected = new byte[10];
        for (int i = 0; i < 10; i++) {
            expected[i] = (byte) (10 + i);
        }
        assertArrayEquals(expected, body(response), "206 正文必须是精确切片，不能多传也不能少传");
    }

    @Test
    void openEndedAndSuffixRangesAreSupported() throws IOException {
        ResponseEntity<?> open = serve(mediaFile(), "bytes=90-", null);
        assertEquals(206, open.getStatusCode().value());
        assertEquals("bytes 90-99/100", open.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE));

        ResponseEntity<?> suffix = serve(mediaFile(), "bytes=-10", null);
        assertEquals(206, suffix.getStatusCode().value());
        assertEquals("bytes 90-99/100", suffix.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE));
    }

    @Test
    void unsatisfiableRangeReturns416WithStarContentRange() throws IOException {
        ResponseEntity<?> response = serve(mediaFile(), "bytes=100-200", null);

        assertEquals(416, response.getStatusCode().value());
        assertEquals("bytes */100", response.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE));
    }

    @Test
    void multiRangeAndForeignUnitsFallBackToFullBody() throws IOException {
        ResponseEntity<?> multi = serve(mediaFile(), "bytes=0-1,5-6", null);
        assertEquals(200, multi.getStatusCode().value());
        assertEquals(100, body(multi).length);

        ResponseEntity<?> foreign = serve(mediaFile(), "chars=0-1", null);
        assertEquals(200, foreign.getStatusCode().value());
    }

    @Test
    void securityAndCacheHeadersSurviveOnPartialContent() throws IOException {
        ResponseEntity<?> response = serve(mediaFile(), "bytes=0-9", null);

        assertEquals(206, response.getStatusCode().value());
        assertEquals("nosniff", response.getHeaders().getFirst("X-Content-Type-Options"));
        assertEquals("default-src 'none'; sandbox", response.getHeaders().getFirst("Content-Security-Policy"));
        assertEquals("private, max-age=300", response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL));
        assertEquals("inline; filename=\"a.mp3\"", response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION));
    }

    @Test
    void ifRangeMismatchFallsBackToFullBody() throws IOException {
        ResponseEntity<?> response = serve(mediaFile(), "bytes=10-19", "\"deadbeef\"");

        assertEquals(200, response.getStatusCode().value(), "校验失败宁可整段重传，也不能传错拼接");
        assertEquals(100, body(response).length);
    }

    @Test
    void ifRangeMatchingEtagKeepsThePartialResponse() throws IOException {
        Path file = mediaFile();
        String etag = serve(file, null, null).getHeaders().getFirst(HttpHeaders.ETAG);

        ResponseEntity<?> response = serve(file, "bytes=10-19", etag);

        assertEquals(206, response.getStatusCode().value());
        assertEquals("bytes 10-19/100", response.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE));
    }
}
