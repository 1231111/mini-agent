package com.miniagent.agent.web;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 生成图落盘时的扩展名判定。
 *
 * <p>为什么单独测：扩展名决定 {@code /api/generated-media} 放不放行
 * （`approvedInlineMediaType` 按扩展名查白名单），判错就等于保存了一张永远 415 的图。
 * 对象存储经常把图片标成 {@code application/octet-stream}，所以必须以文件头魔数为主、content-type 只兜底。</p>
 */
class ImageExtensionTest {

    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    @Test
    void pngMagicWinsOverOctetStreamContentType() {
        byte[] png = bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13);

        assertEquals("png", ImageGenerationService.imageExtension(png, "application/octet-stream"),
                "魔数是 PNG，就不能因为 content-type 不认识而放弃");
    }

    @Test
    void jpegAndGifAndWebpAreRecognised() {
        assertEquals("jpg", ImageGenerationService.imageExtension(
                bytes(0xFF, 0xD8, 0xFF, 0xE0, 0, 0x10, 'J', 'F'), null));
        assertEquals("gif", ImageGenerationService.imageExtension(ascii("GIF89a......"), null));
        assertEquals("webp", ImageGenerationService.imageExtension(ascii("RIFF\0\0\0\0WEBPVP8 "), null));
    }

    @Test
    void contentTypeIsOnlyAFallback() {
        assertEquals("jpg", ImageGenerationService.imageExtension(bytes(1, 2, 3), "image/jpeg"));
        assertEquals("png", ImageGenerationService.imageExtension(bytes(1, 2, 3), "image/png; charset=binary"));
        assertEquals("webp", ImageGenerationService.imageExtension(bytes(1, 2, 3), "IMAGE/WEBP"));
    }

    @Test
    void unknownContentIsRejectedRatherThanGuessed() {
        assertNull(ImageGenerationService.imageExtension(bytes(1, 2, 3, 4), "application/octet-stream"),
                "判不出来就返回 null，让调用方退原链接，不能猜一个扩展名存下去");
        assertNull(ImageGenerationService.imageExtension(new byte[0], null));
        assertNull(ImageGenerationService.imageExtension(null, null));
    }

    /** 智谱免费档返回的是 PNG（文件名带 _watermark），这里只确认能按魔数落到 png。 */
    @Test
    void watermarkedZhipuPngStillMapsToPng() {
        byte[] png = bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A);

        assertEquals("png", ImageGenerationService.imageExtension(png, "image/png"));
    }
}
