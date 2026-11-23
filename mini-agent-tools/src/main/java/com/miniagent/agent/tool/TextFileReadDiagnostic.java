package com.miniagent.agent.tool;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** Actionable, content-based guidance when a UTF-8 text reader is given binary data. */
final class TextFileReadDiagnostic {
    private TextFileReadDiagnostic() {}

    static String forPath(Path path) {
        String kind = detectKnownFormat(path);
        String prefix = "read_file 仅用于 UTF-8 文本，不能解码这个文件";
        if (!kind.isBlank()) prefix += "（检测到 " + kind + "）";
        if ("PNG".equals(kind) || "JPEG".equals(kind)) {
            return prefix + "。不要重复调用 read_file；若它位于当前 workspace，使用 image_inspect 将图片作为视觉证据读取，"
                    + "再结合页面结构或交互测试验收。";
        }
        if ("GIF".equals(kind) || "BMP".equals(kind) || "WebP".equals(kind)) {
            return prefix + "。image_inspect 目前只接受 PNG/JPEG；请使用受支持的格式转换/解析流程，"
                    + "或依据该文件类型的语义工具检查。不要重复调用 read_file。";
        }
        return prefix + "。请按文件类型使用对应的解析/预览工具；图片可用 workspace 内的 image_inspect，"
                + "PDF/Office 文件使用文档解析工具。不要把解码错误当成产品或页面缺陷。";
    }

    private static String detectKnownFormat(Path path) {
        try (InputStream input = Files.newInputStream(path)) {
            byte[] header = input.readNBytes(16);
            if (startsWith(header, 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a)) return "PNG";
            if (startsWith(header, 0xff, 0xd8, 0xff)) return "JPEG";
            if (startsWith(header, 'G', 'I', 'F', '8')) return "GIF";
            if (startsWith(header, 'B', 'M')) return "BMP";
            if (startsWith(header, 'R', 'I', 'F', 'F') && startsWithAt(header, 8, 'W', 'E', 'B', 'P')) return "WebP";
            if (startsWith(header, '%', 'P', 'D', 'F', '-')) return "PDF";
            if (startsWith(header, 'P', 'K', 0x03, 0x04) || startsWith(header, 'P', 'K', 0x05, 0x06)
                    || startsWith(header, 'P', 'K', 0x07, 0x08)) return "ZIP/Office";
            if (startsWith(header, 0xff, 0xfe) || startsWith(header, 0xfe, 0xff)) return "非 UTF-8 文本";
        } catch (IOException ignored) {
            // The original read failure remains the primary error; this is best-effort guidance.
        }
        return "";
    }

    private static boolean startsWith(byte[] bytes, int... expected) {
        return startsWithAt(bytes, 0, expected);
    }

    private static boolean startsWithAt(byte[] bytes, int offset, int... expected) {
        if (bytes == null || bytes.length < offset + expected.length) return false;
        for (int i = 0; i < expected.length; i++) {
            if ((bytes[offset + i] & 0xff) != (expected[i] & 0xff)) return false;
        }
        return true;
    }
}
