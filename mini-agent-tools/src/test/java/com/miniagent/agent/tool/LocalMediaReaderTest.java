package com.miniagent.agent.tool;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 本机图片读取的三道闸：路径（PathGuard）、类型（magic bytes）、大小（配置）。
 *
 * <p>背景：ComfyUI 两个工具此前把模型给的 {@code image_path} 直接 {@code readAllBytes}，
 * 任意路径可读且读到的字节会被 base64 送进模型。这些用例锁住"根之外拒绝"、
 * "伪装扩展名不算图片"、"上限来自配置"。</p>
 */
class LocalMediaReaderTest {

    private final LocalMediaReader reader = new LocalMediaReader(null);

    @Test
    void sniffsImageTypeByContentNotExtension() {
        assertEquals("image/png", LocalMediaReader.sniffImageMime(png(64)));
        assertEquals("image/jpeg", LocalMediaReader.sniffImageMime(
                new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0, 0, 0, 0, 0, 0, 0}));
        assertEquals("image/gif", LocalMediaReader.sniffImageMime(
                "GIF89a".getBytes(StandardCharsets.US_ASCII)));
        assertEquals("image/webp", LocalMediaReader.sniffImageMime(
                "RIFF????WEBPVP8 ".getBytes(StandardCharsets.US_ASCII)));
        assertNull(LocalMediaReader.sniffImageMime(
                        "-----BEGIN OPENSSH PRIVATE KEY-----".getBytes(StandardCharsets.UTF_8)),
                "私钥文本不是图片");
        assertNull(LocalMediaReader.sniffImageMime(new byte[]{1, 2}), "太短的内容不足以判定");
    }

    @Test
    void rejectsPathOutsideAllowedRoots() throws IOException {
        Path outside = Files.createTempDirectory("outside-roots");
        assumeTrue(!PathGuard.isAllowed(outside), "该临时目录应在允许根之外，否则本用例无意义");
        Path secret = outside.resolve("id_rsa");
        Files.writeString(secret, "-----BEGIN OPENSSH PRIVATE KEY-----", StandardCharsets.UTF_8);

        SecurityException e = assertThrows(SecurityException.class, () -> reader.read(secret));
        assertTrue(e.getMessage().contains("拒绝访问工作区外的路径"), e.getMessage());
        assertTrue(e.getMessage().contains("allowed-read-roots"),
                "拒绝消息要告诉运维怎么放行: " + e.getMessage());
    }

    @Test
    void readsImageInsideAllowedRoot() throws IOException {
        Path dir = Files.createDirectories(Path.of(System.getProperty("user.dir"))
                .resolve("target").resolve("media-reader-test"));
        Path image = dir.resolve("shot.png");
        byte[] content = png(4096);
        Files.write(image, content);

        LocalMediaReader.Image read = reader.read(image);
        assertEquals("image/png", read.mimeType());
        assertArrayEquals(content, read.bytes());
        assertEquals(image.toAbsolutePath().normalize(), read.path().toAbsolutePath().normalize());
    }

    @Test
    void disguisedNonImageIsRejectedEvenInsideAllowedRoot() throws IOException {
        Path dir = Files.createDirectories(Path.of(System.getProperty("user.dir"))
                .resolve("target").resolve("media-reader-test"));
        Path disguised = dir.resolve("innocent.png");
        Files.writeString(disguised, "ssh-rsa AAAAB3NzaC1yc2E...\n", StandardCharsets.UTF_8);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> reader.read(disguised));
        assertTrue(e.getMessage().contains("不是受支持的图片格式"),
                "应按内容而不是扩展名判定: " + e.getMessage());
    }

    @Test
    void maxBytesComesFromConfigurationAndIsEnforced() throws IOException, ReflectiveOperationException {
        Path dir = Files.createDirectories(Path.of(System.getProperty("user.dir"))
                .resolve("target").resolve("media-reader-test"));
        Path image = dir.resolve("big.png");
        Files.write(image, png(8192));

        // 上限来自 agent.tools.local-media.max-bytes（配置），这里用反射模拟运维配了一个很小的值
        Field field = LocalMediaReader.class.getDeclaredField("maxBytes");
        field.setAccessible(true);
        LocalMediaReader smallLimitReader = new LocalMediaReader(null);
        field.setLong(smallLimitReader, 1024L);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> smallLimitReader.read(image));
        assertTrue(e.getMessage().contains("agent.tools.local-media.max-bytes"),
                "超限提示要指出改哪个配置项: " + e.getMessage());
    }

    private static byte[] png(int size) {
        byte[] bytes = new byte[size];
        bytes[0] = (byte) 0x89;
        bytes[1] = 'P';
        bytes[2] = 'N';
        bytes[3] = 'G';
        bytes[4] = 0x0D;
        bytes[5] = 0x0A;
        bytes[6] = 0x1A;
        bytes[7] = 0x0A;
        return bytes;
    }
}
