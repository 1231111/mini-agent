package com.miniagent.config.service;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Performs a small, deterministic content sniff before an upload is persisted.
 * A client supplied MIME type or filename is metadata only; unknown binary data
 * is rejected instead of being treated as a harmless octet stream.
 */
final class UploadContentInspector {
    private UploadContentInspector() {}

    record Inspection(boolean accepted, boolean text, String detected, String reason) {}

    static Inspection inspect(String filename, String declaredMime, byte[] head) {
        if (head == null || head.length == 0) {
            return new Inspection(false, false, "unknown", "empty upload");
        }
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        String mime = declaredMime == null ? "" : declaredMime.toLowerCase(Locale.ROOT);
        String detected;
        boolean text = false;
        if (starts(head, "%PDF-")) detected = "pdf";
        else if (starts(head, new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a})) detected = "image";
        else if (starts(head, new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff})) detected = "image";
        else if (starts(head, "GIF87a") || starts(head, "GIF89a")) detected = "image";
        else if (starts(head, "BM") || starts(head, new byte[]{'I', 'I', 0x2a, 0x00})
                || starts(head, new byte[]{'M', 'M', 0x00, 0x2a})) detected = "image";
        else if (starts(head, "RIFF") && containsAscii(head, 8, "WEBP")) detected = "image";
        else if (starts(head, "RIFF") && containsAscii(head, 8, "WAVE")) detected = "audio";
        else if (starts(head, "RIFF") && containsAscii(head, 8, "AVI ")) detected = "video";
        else if (starts(head, "OggS") || starts(head, "fLaC") || starts(head, "ID3")
                || (head.length >= 2 && (head[0] & 0xff) == 0xff && (head[1] & 0xe0) == 0xe0)) detected = "audio";
        else if (head.length >= 12 && containsAscii(head, 4, "ftyp")) detected = "video";
        else if (starts(head, new byte[]{0x1f, (byte) 0x8b})) detected = "archive";
        else if (starts(head, new byte[]{'P', 'K', 0x03, 0x04})) detected = "archive";
        else if (starts(head, new byte[]{(byte) 0xd0, (byte) 0xcf, 0x11, (byte) 0xe0,
                (byte) 0xa1, (byte) 0xb1, 0x1a, (byte) 0xe1})) detected = "ole";
        else if (looksLikeText(head)) { detected = "text"; text = true; }
        else detected = "unknown";

        if ("unknown".equals(detected)) {
            return new Inspection(false, false, detected,
                    "无法确认上传内容类型；请使用受支持的文档、媒体或文本格式");
        }
        if (mime.startsWith("text/") && !text) {
            return new Inspection(false, false, detected, "声明为文本但内容不是文本");
        }
        if ((mime.startsWith("image/") && !"image".equals(detected))
                || (mime.startsWith("audio/") && !"audio".equals(detected))
                || (mime.startsWith("video/") && !"video".equals(detected))) {
            return new Inspection(false, text, detected, "声明的 MIME 类型与文件内容不一致");
        }
        if (("archive".equals(detected) || "ole".equals(detected)) && !archiveName(name)) {
            return new Inspection(false, false, detected,
                    "压缩内容必须使用受支持的 Office、归档或压缩文件扩展名");
        }
        if ("pdf".equals(detected) && !mime.isBlank() && !mime.equals("application/pdf")
                && !mime.equals("application/octet-stream")) {
            return new Inspection(false, false, detected, "PDF 内容与声明的 MIME 类型不一致");
        }
        return new Inspection(true, text, detected, "ok");
    }

    private static boolean archiveName(String name) {
        return name.endsWith(".zip") || name.endsWith(".gz") || name.endsWith(".tgz")
                || name.endsWith(".docx") || name.endsWith(".xlsx") || name.endsWith(".pptx")
                || name.endsWith(".doc") || name.endsWith(".xls") || name.endsWith(".ppt")
                || name.endsWith(".odt") || name.endsWith(".ods") || name.endsWith(".odp");
    }

    private static boolean looksLikeText(byte[] bytes) {
        int printable = 0;
        int considered = Math.min(bytes.length, 512);
        for (int i = 0; i < considered; i++) {
            int b = bytes[i] & 0xff;
            if (b == 0) return false;
            if (b == '\n' || b == '\r' || b == '\t' || (b >= 0x20 && b != 0x7f)) printable++;
        }
        if (considered == 0 || printable * 100 < considered * 85) return false;
        String value = new String(bytes, 0, considered, StandardCharsets.UTF_8);
        return !value.contains("\ufffd");
    }

    private static boolean starts(byte[] value, String prefix) {
        return starts(value, prefix.getBytes(StandardCharsets.US_ASCII));
    }

    private static boolean starts(byte[] value, byte[] prefix) {
        if (value.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (value[i] != prefix[i]) return false;
        return true;
    }

    private static boolean containsAscii(byte[] value, int offset, String text) {
        if (offset < 0 || value.length < offset + text.length()) return false;
        for (int i = 0; i < text.length(); i++) if (value[offset + i] != (byte) text.charAt(i)) return false;
        return true;
    }
}
