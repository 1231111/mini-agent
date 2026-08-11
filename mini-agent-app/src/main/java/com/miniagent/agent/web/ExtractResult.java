package com.miniagent.agent.web;

/**
 * 文件文本提取结果（工业级：可判断成功/截断/侧车路径，禁止把异常当正文吞掉却不标记）。
 */
public record ExtractResult(
        boolean success,
        String format,
        String text,
        int originalChars,
        boolean truncated,
        String error,
        String extractedTextPath
) {
    public static ExtractResult ok(String format, String text, boolean truncated, int originalChars) {
        return new ExtractResult(true, format, text == null ? "" : text, originalChars, truncated, null, null);
    }

    public static ExtractResult okWithSidecar(String format, String text, boolean truncated,
                                              int originalChars, String sidecarPath) {
        return new ExtractResult(true, format, text == null ? "" : text, originalChars, truncated, null, sidecarPath);
    }

    public static ExtractResult fail(String format, String error) {
        return new ExtractResult(false, format == null ? "unknown" : format, "", 0, false, error, null);
    }

    public boolean hasText() {
        return success && text != null && !text.isBlank();
    }
}
