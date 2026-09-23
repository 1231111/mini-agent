package com.miniagent.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 媒体文件响应的共用出口（{@code /api/generated-media}、{@code /api/conversation-media}）。
 *
 * <h2>为什么要有它</h2>
 *
 * <p>改造前两个端点直接 {@code ResponseEntity.ok().body(UrlResource)}，不响应 Range ——
 * 音频拖进度条时浏览器发 {@code Range: bytes=x-y}，服务端却把整文件当 200 重发一遍，
 * 拖动直接失效。这里补上 Range 全套：{@code Accept-Ranges} 声明、单段 206 +
 * {@code Content-Range}、不可满足 416、{@code If-Range} 校验失败退整段。</p>
 *
 * <h2>口径</h2>
 * <ul>
 *   <li>单段 Range → 206；多段 / 语法非法 / 无 Range → 200 整段（解析口径见 {@link ByteRanges}）。</li>
 *   <li>{@code If-Range} 只做基础校验：ETag 精确相等，或日期与 {@code Last-Modified} 的
 *       RFC 1123 表述精确相等；不匹配就退整段 200（宁可多传，不可传错）。</li>
 *   <li>206 正文用「到界即 EOF」的包装流（{@link BoundedInputStream}），
 *       {@code Content-Length} 显式声明为切片长度 —— 声明了就不让转换器再去数流，
 *       一次性流不会被提前读光。</li>
 *   <li>安全头（{@code nosniff} / CSP sandbox）与缓存头在 200/206/416 上原样保留。</li>
 * </ul>
 */
public final class MediaResponses {

    private MediaResponses() {
    }

    /** 带 Range 的媒体返回。鉴权与路径安全由调用方完成。 */
    public static ResponseEntity<?> serve(Path media, String filename, String contentType,
                                          HttpServletRequest request) throws IOException {
        long size = Files.size(media);
        long lastModified = Files.getLastModifiedTime(media).toMillis();
        String etag = "\"" + Long.toHexString(lastModified) + "-" + Long.toHexString(size) + "\"";

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Content-Security-Policy", "default-src 'none'; sandbox");
        headers.set(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"");
        headers.set(HttpHeaders.CACHE_CONTROL, "private, max-age=300");
        headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");
        headers.set(HttpHeaders.ETAG, etag);
        headers.set(HttpHeaders.LAST_MODIFIED, formatHttpDate(lastModified));
        MediaType type = MediaType.parseMediaType(contentType);

        String rangeHeader = request == null ? null : request.getHeader(HttpHeaders.RANGE);
        String ifRange = request == null ? null : request.getHeader(HttpHeaders.IF_RANGE);
        boolean useRange = rangeHeader != null && matchesIfRange(ifRange, etag, lastModified);

        if (useRange) {
            ByteRanges.Result result = ByteRanges.parse(rangeHeader, size);
            if (result.kind() == ByteRanges.Kind.UNSATISFIABLE) {
                headers.set(HttpHeaders.CONTENT_RANGE, "bytes */" + size);
                return ResponseEntity.status(416).headers(headers).contentType(type).build();
            }
            if (result.kind() == ByteRanges.Kind.SLICE) {
                ByteRanges.Slice slice = result.slice();
                headers.set(HttpHeaders.CONTENT_RANGE,
                        "bytes " + slice.start() + "-" + slice.end() + "/" + size);
                headers.setContentLength(slice.end() - slice.start() + 1);
                return ResponseEntity.status(206).headers(headers).contentType(type)
                        .body(boundedResource(media, slice));
            }
        }
        headers.setContentLength(size);
        return ResponseEntity.ok().headers(headers).contentType(type)
                .body(new UrlResource(media.toUri()));
    }

    /**
     * 只暴露 {@code [start, end]} 一段的资源：先跳到起点，再由 {@link BoundedInputStream}
     * 到界即 EOF。{@code Content-Length} 由调用方显式声明，转换器不会再去数流。
     */
    private static InputStreamResource boundedResource(Path media, ByteRanges.Slice slice) throws IOException {
        InputStream in = Files.newInputStream(media);
        try {
            in.skipNBytes(slice.start());
        } catch (IOException e) {
            in.close();
            throw e;
        }
        return new InputStreamResource(new BoundedInputStream(in, slice.end() - slice.start() + 1));
    }

    /** If-Range 基础校验：ETag 精确相等，或日期与 Last-Modified 的 RFC 1123 表述精确相等。 */
    private static boolean matchesIfRange(String ifRange, String etag, long lastModified) {
        if (ifRange == null) {
            return true;
        }
        String value = ifRange.trim();
        if (value.startsWith("\"") || value.startsWith("W/")) {
            return value.equals(etag);
        }
        return value.equals(formatHttpDate(lastModified));
    }

    /** RFC 1123 日期（GMT），与 Last-Modified / If-Range 的比对口径一致。 */
    private static String formatHttpDate(long epochMillis) {
        return DateTimeFormatter.RFC_1123_DATE_TIME.format(
                Instant.ofEpochMilli(epochMillis).atZone(ZoneOffset.UTC));
    }

    /** 到界即 EOF 的包装流：保证 206 正文长度与 Content-Length 精确一致。 */
    static final class BoundedInputStream extends FilterInputStream {
        private long remaining;

        BoundedInputStream(InputStream in, long length) {
            super(in);
            this.remaining = Math.max(0, length);
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int b = super.read();
            if (b >= 0) {
                remaining--;
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int n = super.read(b, off, (int) Math.min(len, remaining));
            if (n > 0) {
                remaining -= n;
            }
            return n;
        }

        @Override
        public long skip(long n) throws IOException {
            long skipped = super.skip(Math.min(n, remaining));
            remaining -= skipped;
            return skipped;
        }
    }
}
